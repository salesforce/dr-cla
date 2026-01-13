/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package controllers

import java.time.LocalDateTime
import java.net.URL

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Query
import javax.inject.Inject
/*
import javax.inject.Singleton
*/
import models._
import org.apache.commons.codec.digest.HmacUtils
import org.webjars.WebJarAssetLocator
import org.webjars.play.WebJarsUtil
import play.api.http.HttpErrorHandler
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.mvc.Results.EmptyContent
import play.api.mvc._
import play.api.{Configuration, Environment, Logger}
import play.twirl.api.Html
import utils.{Crypto, DB, GitHub}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.xml.{Comment, Node}


class Application @Inject()
  (env: Environment, gitHub: GitHub, db: DB, crypto: Crypto, configuration: Configuration, webJarsUtil: WebJarsUtil)
  (claSignView: views.html.claSign, claSignedView: views.html.claSigned, claAlreadySignedView: views.html.claAlreadySigned, claStatusView: views.html.claStatus, auditView: views.html.audit, auditReposView: views.html.auditRepos)
  (viewHelper: helpers.ViewHelpers)
  (implicit ec: ExecutionContext)
  extends InjectedController {

  val claVersions = Set("0.0")
  val latestClaVersion = claVersions.head

  val gitHubOauthScopesForClaSigning = Seq("user:email")
  val maybeOrgEmail = configuration.getOptional[String]("app.organization.email")

  def index = Action { request =>
    viewHelper.maybeOrganizationUrl.fold(Redirect(routes.Application.signCla(None)))(Redirect(_))
  }

  def wellKnown(key: String) = Action {
    configuration.getOptional[String]("wellknown").fold(NotFound(EmptyContent())) { wellKnownKeyValue =>
      if (wellKnownKeyValue.startsWith(key + "=")) {
        Ok(wellKnownKeyValue.stripPrefix(key + "="))
      }
      else {
        NotFound(EmptyContent())
      }
    }
  }

  def gitHubAppOauthCallback(code: String, state: String) = Action.async { implicit request =>
    gitHub.accessToken(code, gitHub.integrationClientId, gitHub.integrationClientSecret).map { accessToken =>
      val encAccessToken = crypto.encryptAES(accessToken)
      Redirect(safeRedirectUrl(state)).flashing("encAccessToken" -> encAccessToken)
    } recover {
      case e: utils.UnauthorizedError => Redirect(safeRedirectUrl(state)).flashing("error" -> e.getMessage)
      case e: Exception => InternalServerError(e.getMessage)
    }
  }

  // state is used for the URL to redirect to
  def gitHubOauthCallback(code: String, state: String) = Action.async { implicit request =>
    gitHub.accessToken(code, gitHub.clientId, gitHub.clientSecret).map { accessToken =>
      val encAccessToken = crypto.encryptAES(accessToken)
      Redirect(safeRedirectUrl(state)).flashing("encAccessToken" -> encAccessToken)
    } recover {
      case e: utils.UnauthorizedError => Redirect(safeRedirectUrl(state)).flashing("error" -> e.getMessage)
      case e: Exception => InternalServerError(e.getMessage)
    }
  }

  def signCla(maybePrUrl: Option[String]) = Action.async { implicit request =>
    getGitHubAuthInfo(request).flatMap { maybeGitHubAuthInfo =>
      val claSignatureExistsFuture = maybeGitHubAuthInfo.fold(Future.unit) { gitHubAuthInfo =>
        db.findClaSignaturesByGitHubIds(Set(gitHubAuthInfo.user)).flatMap { claSignatures =>
          claSignatures.headOption.fold(Future.unit) { claSignature =>
            Future.failed(AlreadyExistsException(claSignature))
          }
        }
      }

      claSignatureExistsFuture.map { _ =>
        val authUrl = gitHubAuthUrl(gitHubOauthScopesForClaSigning, routes.Application.signCla(maybePrUrl).absoluteURL())
        Ok(claSignView(authUrl, maybeGitHubAuthInfo, latestClaVersion, viewHelper.claText, maybePrUrl, svgInline))
      } recover {
        case AlreadyExistsException(claSignature) =>
          BadRequest(claAlreadySignedView(claSignature.signedOn))
      }
    }
  }

  def submitCla(maybePrUrl: Option[String]) = Action.async(parse.formUrlEncoded) { implicit request =>
    val maybeClaSignatureFuture = for {
      encGitHubToken <- request.body.get("encGitHubToken").flatMap(_.headOption)
      claVersion <- claVersions.find(request.body.get("claVersion").flatMap(_.headOption).contains)
      fullName <- request.body.get("fullName").flatMap(_.headOption).filter(_.nonEmpty)
      (maybeFirstName, maybeLastName) = Contact.fullNameToFirstAndLast(fullName)
      lastName <- maybeLastName
      email <- request.body.get("email").flatMap(_.headOption)
      agreeToCLA <- request.body.get("agreeToCLA").flatMap(_.headOption)
    } yield {
        if (agreeToCLA == "on") {
          val gitHubToken = crypto.decryptAES(encGitHubToken)

          for {
            user <- gitHub.userInfo(gitHubToken)
            authInfo = GitHub.AuthInfo(encGitHubToken, user)

            maybeContact <- db.findContactByGitHubId(user.username)
            contact <- maybeContact.fold {
              db.createContact(Contact(-1, maybeFirstName, lastName, email, user.username))
            } (Future.successful)
            existingClaSignatures <- db.findClaSignaturesByGitHubIds(Set(user))
            claSignature <- existingClaSignatures.headOption.fold {
              Future.successful(ClaSignature(-1, contact.gitHubId, LocalDateTime.now(), claVersion))
            } { existingClaSignature =>
              Future.failed(AlreadyExistsException(existingClaSignature))
            }
          } yield (claSignature, authInfo)
        } else {
          Future.failed(new IllegalStateException("The CLA was not agreed to."))
        }
    }

    maybeClaSignatureFuture.fold {
      Future.successful(BadRequest("A required field was not specified."))
    } { claSignatureFuture =>
      def validatePullRequestOrRequests(claSignature: ClaSignature, authInfo: GitHub.AuthInfo): Future[Unit] = {
        maybePrUrl.fold {
          // do not block on this
          revalidatePullRequests(authInfo.user).failed.foreach { e =>
            Logger.error("Could not revalidate PRs", e)
          }

          Future.unit
        } { prUrl =>
          val (ownerRepo, prNum) = GitHub.pullRequestInfo(prUrl)
          revalidatePullRequest(ownerRepo, prNum, Some(authInfo)).map(_ => Unit)
        }
      }

      for {
        (claSignature, authInfo) <- claSignatureFuture
        persistedClaSignature <- db.createClaSignature(claSignature)
        validatePullRequestOrRequests <- validatePullRequestOrRequests(claSignature, authInfo)
      } yield Redirect(routes.Application.signedCla(maybePrUrl))
    } recover {
      case AlreadyExistsException(claSignature) =>
        BadRequest(claAlreadySignedView(claSignature.signedOn))
      case e: Throwable =>
        Logger.error("CLA could not be signed.", e)
        val baseErrorMessage = "Could not sign the CLA"
        val errorMessage = maybeOrgEmail.fold(baseErrorMessage) { orgEmail =>
          baseErrorMessage + ", please contact: " + orgEmail
        }
        InternalServerError(errorMessage)
    }
  }

  def signedCla(maybePrUrl: Option[String]) = Action { request =>
    Ok(claSignedView(maybePrUrl))
  }

  case class NoPullRequest() extends Exception {
    override def getMessage: String = "A pull request could not be found"
  }

  private def handlePullRequest(jsValue: JsValue, token: String)(implicit request: RequestHeader): Future[(Set[GitHub.Contributor], Set[GitHub.Contributor], JsObject)] = {
    (jsValue \ "pull_request").asOpt[JsValue].fold(Future.failed[(Set[GitHub.Contributor], Set[GitHub.Contributor], JsObject)](NoPullRequest())) { pullRequest =>
      for {
        pullRequestWithDetails <- gitHub.pullRequestWithCommitsAndStatus(token)(pullRequest)
        validate <- validatePullRequest(pullRequestWithDetails, token)
      } yield validate
    }
  }

  def webhookIntegration = Action.async(parse.json) { implicit request =>
    val maybeHubSignature = request.headers.get("X-Hub-Signature")

    // first check if a signature was sent, if not then we don't need auth
    val authorized = maybeHubSignature.fold(true) { hubSignature =>
      // if a signature was sent, validate it against a configured secret token
      gitHub.maybeIntegrationSecretToken.fold(false) { integrationSecretToken =>
        hubSignature == "sha1=" + HmacUtils.hmacSha1Hex(integrationSecretToken, request.body.toString())
      }
    }

    if (authorized) {
      val maybeHandlePrivate = configuration.getOptional[String]("app.organization.handle-private").exists(_.trim.nonEmpty)
      val maybeEvent = request.headers.get("X-GitHub-Event")

      if (maybeEvent.contains("pull_request") || maybeEvent.contains("issue_comment")) {

        // Check if repository is public - only process webhooks for public repositories
        val isPrivate = (request.body \ "repository" \ "private").asOpt[Boolean]
          .orElse((request.body \ "pull_request" \ "base" \ "repo" \ "private").asOpt[Boolean])
          .orElse((request.body \ "pull_request" \ "head" \ "repo" \ "private").asOpt[Boolean])
          .orElse((request.body \ "issue" \ "repository" \ "private").asOpt[Boolean])
          .getOrElse(false)

        if (isPrivate) {
          Future.successful(Ok("Skipping webhook for private repository"))
        } else {
          val maybeAction = (request.body \ "action").asOpt[String]

          maybeAction.fold {
            Future.successful {
              (request.body \ "zen").asOpt[String].fold {
                BadRequest("Was this a test?  If so, where is your zen?")
              } { zen =>
                Ok(zen)
              }
            }
          } {
            case "opened" | "reopened" | "synchronize" | "created" | "edited" =>
              val installationId = (request.body \ "installation" \ "id").as[Int]
              val handlePullRequestFuture = for {
                token <- gitHub.installationAccessTokens(installationId).map(_.\("token").as[String])
                _ <- handlePullRequest(request.body, token)
              } yield Ok

              handlePullRequestFuture.recover {
                case e: Exception =>
                  Logger.error("Error handling pull request", e)
                  InternalServerError(e.getMessage)
              }
            case action: String =>
              Future.successful(Ok(s"Did nothing for the action = $action"))
          }
        }
      }
      else {
        Future.successful {
          maybeEvent.fold(BadRequest("No event was specified")) { event =>
            Ok(s"Did nothing for event = $event")
          }
        }
      }
    }
    else {
      Future.successful(Unauthorized)
    }
  }

  def status(ownerRepo: GitHub.OwnerRepo, prNum: Int) = Action.async { implicit request =>
    getGitHubAuthInfo(request).flatMap { maybeGitHubAuthInfo =>
      revalidatePullRequest(ownerRepo, prNum, maybeGitHubAuthInfo).map { case (_, claMissing, _) =>
        val claUrl = routes.Application.signCla(Some(GitHub.pullRequestUrl(ownerRepo, prNum))).absoluteURL()
        Ok(claStatusView(ownerRepo, prNum, claMissing, claUrl))
      } recover {
        case NeedsAuth =>
          Redirect(gitHubAppAuthUrl(routes.Application.status(ownerRepo, prNum).absoluteURL()))
        case irs: GitHub.IncorrectResponseStatus if irs.actualStatusCode == NOT_FOUND =>
          NotFound("Repo Not Found")
      }
    }
  }

  def audit = Action.async { implicit request =>
    getGitHubAuthInfo(request).flatMap { maybeGitHubAuthInfo =>
      maybeGitHubAuthInfo.fold {
        Future.successful(Redirect(gitHubAppAuthUrl(routes.Application.audit().absoluteURL())))
      } { gitHubAuthInfo =>
        val userAccessToken = crypto.decryptAES(gitHubAuthInfo.encAuthToken)

        gitHub.userInstallations(userAccessToken).flatMap { installations =>
          val ids = installations.value.map(_.\("id").as[Int])

          val repoFutures = ids.map { id =>
            gitHub.installationRepositories(id, userAccessToken).recover {
              case irs: GitHub.IncorrectResponseStatus => JsArray.empty
            }
          }.toList

          Future.foldLeft(repoFutures)(JsArray.empty)(_ ++ _).map { repos =>
            val orgRepos = repos.value.map { repoJson =>
              val org = (repoJson \ "owner" \ "login").as[String]
              val repo = repoJson.as[GitHub.OwnerRepo]

              org -> repo
            }.groupBy(_._1).mapValues(_.map(_._2))

            Ok(auditView(orgRepos, gitHub.integrationSlug, gitHubAuthInfo.encAuthToken))
          }
        } recover {
          case irs: GitHub.IncorrectResponseStatus =>
            Logger.error("Audit Error", irs)
            InternalServerError(irs.message)
          case e: Exception =>
            Logger.error("Audit Error", e)
            InternalServerError(e.getMessage)
        }
      }
    }
  }

  def auditContributors(ownerRepo: GitHub.OwnerRepo, encAccessToken: String) = Action.async { implicit request =>
    val accessToken = crypto.decryptAES(encAccessToken)

    val orgMembersFuture = gitHub.orgMembers(ownerRepo.owner, accessToken)
    val allContributorsFuture = gitHub.repoContributors(ownerRepo, accessToken)

    val future = for {
      orgMembers <- orgMembersFuture
      allContributors <- allContributorsFuture
      externalContributors = gitHub.externalContributors(allContributors.map(_.contributor), orgMembers.toSet[GitHub.Contributor])
      gitHubUsers = externalContributors.collect { case gitHubUser: GitHub.User => gitHubUser }
      clasForExternalContributors <- db.findClaSignaturesByGitHubIds(gitHubUsers)
    } yield {

      val (internalContributors, external) = allContributors.partition { case GitHub.ContributorWithMetrics(contributor, _) =>
        contributor match {
          case user: GitHub.User => orgMembers.contains(user)
          case _ => false
        }
      }

      val externalContributorsWithClas = external.map { contributorWithMetrics =>
        contributorWithMetrics.contributor match {
          case gitHubUser: GitHub.User =>
            contributorWithMetrics -> clasForExternalContributors.find(_.contactGitHubId == gitHubUser.username)
          case _ =>
            contributorWithMetrics -> None
        }
      }

      (externalContributorsWithClas, internalContributors)
    }

    future.map { case (externalContributorsWithClas, internalContributors) =>
      Ok(views.html.auditRepo(externalContributorsWithClas, internalContributors))
    } recover {
      case irs: GitHub.IncorrectResponseStatus =>
        Logger.error("Audit Error", irs)
        InternalServerError(irs.message)
      case e: Exception =>
        Logger.error("Audit Error", e)
        InternalServerError(e.getMessage)
    }
  }

  private[controllers] def svgSymbol(path: String, symbol: String): Node = {
    webJarsUtil.locate(path).path.flatMap { filePath =>
      val maybeInputStream = env.resourceAsStream(WebJarAssetLocator.WEBJARS_PATH_PREFIX + "/" + filePath)
      maybeInputStream.fold[Try[Node]](Failure(new Exception("Could not read file"))) { inputStream =>
        val elem = scala.xml.XML.load(inputStream)
        inputStream.close()

        val maybeSymbol = elem.child.find { node =>
          node \@ "id" == symbol
        } flatMap (_.child.headOption)

        maybeSymbol.fold[Try[Node]](Failure(new Exception(s"Could not find symbol $symbol")))(Success(_))
      }
    } fold (
      { t => Comment(s"Error getting SVG: ${t.getMessage}") },
      { identity }
    )
  }

  private def svgInline(path: String, symbol: String): Html = {
    Html(svgSymbol(path, symbol).toString())
  }

  private def getGitHubAuthInfo(request: RequestHeader): Future[Option[GitHub.AuthInfo]] = {
    request.flash.get("encAccessToken").fold {
      Future.successful[Option[GitHub.AuthInfo]](None)
    } { encAccessToken =>
      val accessToken = crypto.decryptAES(encAccessToken)
      gitHub.userInfo(accessToken).map { user =>
        Some(GitHub.AuthInfo(encAccessToken, user))
      }
    }
  }

  private def validatePullRequest(pullRequestToBeValidated: JsObject, token: String)(implicit request: RequestHeader): Future[GitHub.ValidationResult] = {
    val (ownerRepo, prNum) = GitHub.pullRequestInfo(pullRequestToBeValidated)
    val claUrl = routes.Application.signCla(Some(GitHub.pullRequestUrl(ownerRepo, prNum))).absoluteURL()
    val statusUrl = routes.Application.status(ownerRepo, prNum).absoluteURL()

    gitHub.validatePullRequest(pullRequestToBeValidated, token, claUrl, statusUrl) { externalCommitters =>
      val gitHubUsers = externalCommitters.collect { case gitHubUser: GitHub.User => gitHubUser }
      db.findClaSignaturesByGitHubIds(gitHubUsers)
    }
  }

  private def validatePullRequests(pullRequestsToBeValidated: Map[JsObject, String])(implicit request: RequestHeader): Future[Iterable[GitHub.ValidationResult]] = {
    val claUrlF = (ownerRepo: GitHub.OwnerRepo, prNum: Int) => routes.Application.signCla(Some(GitHub.pullRequestUrl(ownerRepo, prNum))).absoluteURL()
    val statusUrlF = (ownerRepo: GitHub.OwnerRepo, prNum: Int) => routes.Application.status(ownerRepo, prNum).absoluteURL()
    gitHub.validatePullRequests(pullRequestsToBeValidated, claUrlF, statusUrlF) { externalCommitters =>
      val gitHubUsers = externalCommitters.collect { case gitHubUser: GitHub.User => gitHubUser }
      db.findClaSignaturesByGitHubIds(gitHubUsers)
    }
  }

  // When someone signs the CLA we don't know what PR we need to update.
  // So get all the PRs we have access to, that have the contributor which just signed the CLA and where the status is failed.
  private def revalidatePullRequests(signerGitHub: GitHub.User)(implicit request: RequestHeader): Future[Iterable[GitHub.ValidationResult]] = {
    for {
      pullRequestsToBeValidated <- gitHub.pullRequestsToBeValidated(signerGitHub)
      validation <- validatePullRequests(pullRequestsToBeValidated)
    } yield validation
  }

  private def revalidatePullRequest(ownerRepo: GitHub.OwnerRepo, prNum: Int, maybeGitHubAuthInfo: Option[GitHub.AuthInfo])(implicit request: RequestHeader): Future[GitHub.ValidationResult] = {
    gitHub.integrationInstallations().flatMap { installations =>
      val maybeOrgInstall = installations.as[Seq[JsObject]].find { repoJson =>
        repoJson.as[GitHub.Owner] == ownerRepo.owner
      }

      maybeOrgInstall.fold(Future.failed[GitHub.ValidationResult](new Exception(s"GitHub App not installed on ${ownerRepo.owner}"))) { orgInstall =>
        val id = (orgInstall \ "id").as[Int]
        gitHub.installationAccessTokens(id).flatMap { installationAccessTokenJson =>
          val installationAccessToken = (installationAccessTokenJson \ "token").as[String]

          gitHub.installationRepositories(installationAccessToken).flatMap { installationRepositories =>

            val maybeRepo = installationRepositories.value.find { repoJson =>
              repoJson.asOpt[GitHub.OwnerRepo].contains(ownerRepo)
            }

            maybeRepo.fold(Future.failed[GitHub.ValidationResult](new Exception(s"GitHub App not enabled on $ownerRepo"))) { repoJson =>
              val isPrivate = (repoJson \ "private").as[Boolean]

              def getAndValidatePullRequest(fetchAccessToken: String): Future[GitHub.ValidationResult] = {
                gitHub.getPullRequest(ownerRepo, prNum, fetchAccessToken).flatMap { pullRequest =>
                  gitHub.pullRequestWithCommitsAndStatus(fetchAccessToken)(pullRequest).flatMap { pullRequestDetails =>
                    validatePullRequest(pullRequestDetails, installationAccessToken)
                  }
                }
              }

              if (isPrivate) {
                maybeGitHubAuthInfo.fold {
                  Future.failed[GitHub.ValidationResult](NeedsAuth)
                } { gitHubAuthInfo =>
                  val userAccessToken = crypto.decryptAES(gitHubAuthInfo.encAuthToken)
                  getAndValidatePullRequest(userAccessToken)
                }
              }
              else {
                getAndValidatePullRequest(installationAccessToken)
              }
            }
          }
        }
      }
    }.recover {
      case e: Exception =>
        Logger.error(s"Error revalidating pull request $ownerRepo#$prNum", e)
        (Set.empty[GitHub.Contributor], Set.empty[GitHub.Contributor], Json.obj())
    }
  }

  private def gitHubAppAuthUrl(state: String)(implicit request: RequestHeader): String = {
    val query = Query("client_id" -> gitHub.integrationClientId, "redirect_uri" -> redirectAppUri, "state" -> state)
    val uri = Uri("https://gitHub.com/login/oauth/authorize")
    uri.withQuery(query).toString()
  }

  private def gitHubAuthUrl(scopes: Seq[String], state: String)(implicit request: RequestHeader): String = {
    val query = Query("client_id" -> gitHub.clientId, "redirect_uri" -> redirectUri, "scope" -> scopes.mkString(" "), "state" -> state)
    val uri = Uri("https://gitHub.com/login/oauth/authorize")

    uri.withQuery(query).toString()
  }

  private def redirectAppUri(implicit request: RequestHeader): String = {
    routes.Application.gitHubAppOauthCallback("", "").absoluteURL(request.secure).stripSuffix("?code=&state=")
  }

  private def redirectUri(implicit request: RequestHeader): String = {
    routes.Application.gitHubOauthCallback("", "").absoluteURL(request.secure).stripSuffix("?code=&state=")
  }

  private def safeRedirectUrl(state: String)(implicit request: RequestHeader): String = {
      // If passed in redirectUrl (state) is outside app domain, redirect to base sign-cla form instead
      // Needed to mitigate OWASP unvalidated redirects
      val appUrl = new URL(routes.Application.signCla(None).absoluteURL())
      val redirectUrl = new URL(state)

      if(redirectUrl.getHost() != appUrl.getHost()){
        appUrl.toString()
      }
      else {
        redirectUrl.toString()
      }
  }

  case class AlreadyExistsException(claSignature: ClaSignature) extends Exception

  case object NeedsAuth extends Exception

/*
  @Singleton
  class ErrorHandler extends HttpErrorHandler {
    def onClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] = {
      Future.successful(
        Status(statusCode)("A client error occurred: " + message)
      )
    }

    def onServerError(request: RequestHeader, exception: Throwable): Future[Result] = {
      Future.successful(
        InternalServerError("A server error occurred: " + exception.getMessage)
      )
    }
  }
*/
}
