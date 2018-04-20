/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package controllers

import java.time.LocalDateTime
import javax.inject.Inject

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Query
import models._
import org.apache.commons.codec.digest.HmacUtils
import org.webjars.WebJarAssetLocator
import org.webjars.play.WebJarsUtil
import play.api.{Configuration, Environment, Logger}
import play.api.libs.json.{JsObject, JsValue}
import play.api.mvc.Results.EmptyContent
import play.api.mvc._
import play.twirl.api.Html
import utils.GitHub.IncorrectResponseStatus
import utils.{Crypto, DB, GitHub}

import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.util.{Failure, Success, Try}
import scala.xml.{Comment, Node}


class Application @Inject()
  (env: Environment, gitHub: GitHub, db: DB, crypto: Crypto, configuration: Configuration, webJarsUtil: WebJarsUtil)
  (claSignView: views.html.claSign, claSignedView: views.html.claSigned, claAlreadySignedView: views.html.claAlreadySigned, claStatusView: views.html.claStatus, auditView: views.html.audit, auditReposView: views.html.auditRepos, auditError: views.html.auditError)
  (viewHelper: helpers.ViewHelpers)
  (implicit ec: ExecutionContext)
  extends InjectedController {

  val claVersions = Set("0.0")
  val latestClaVersion = claVersions.head

  val gitHubOauthScopesForClaSigning = Seq("user:email")
  val gitHubOauthScopesForAudit = Seq("read:org")
  val maybeOrgEmail = configuration.getOptional[String]("app.organization.email")
  val gitHubOauthScopesForStatus = Seq("repo") // todo: there isn't an oauth scope that allows read-only access to private repos in other accounts

  def index = Action {
    viewHelper.maybeOrganizationUrl.fold(Redirect(routes.Application.signCla()))(Redirect(_))
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

  // state is used for the URL to redirect to
  def gitHubOauthCallback(code: String, state: String) = Action.async { request =>
    gitHub.accessToken(code).map { accessToken =>
      val encAccessToken = crypto.encryptAES(accessToken)
      Redirect(state).flashing("encAccessToken" -> encAccessToken)
    } recover {
      case e: utils.UnauthorizedError => Redirect(state).flashing("error" -> e.getMessage)
      case e: Exception => InternalServerError(e.getMessage)
    }
  }

  def signCla = Action.async { implicit request =>
    getGitHubAuthInfo(request).flatMap { maybeGitHubAuthInfo =>
      val claSignatureExistsFuture = maybeGitHubAuthInfo.fold(Future.unit) { gitHubAuthInfo =>
        db.findClaSignaturesByGitHubIds(Set(GitHub.GitHubUser(gitHubAuthInfo.userName))).flatMap { claSignatures =>
          claSignatures.headOption.fold(Future.unit) { claSignature =>
            Future.failed(AlreadyExistsException(claSignature))
          }
        }
      }

      claSignatureExistsFuture.map { _ =>
        val authUrl = gitHubAuthUrl(gitHubOauthScopesForClaSigning, routes.Application.signCla().absoluteURL())
        Ok(claSignView(authUrl, maybeGitHubAuthInfo, latestClaVersion, viewHelper.claText, svgInline))
      } recover {
        case AlreadyExistsException(claSignature) =>
          BadRequest(claAlreadySignedView(claSignature.signedOn))
      }
    }
  }

  def submitCla = Action.async(parse.formUrlEncoded) { implicit request =>
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
            userInfo <- gitHub.userInfo(gitHubToken)
            username = (userInfo \ "login").as[String]
            maybeContact <- db.findContactByGitHubId(username)
            contact <- maybeContact.fold {
              db.createContact(Contact(-1, maybeFirstName, lastName, email, username))
            } (Future.successful)
            existingClaSignatures <- db.findClaSignaturesByGitHubIds(Set(GitHub.GitHubUser(username)))
            claSignature <- existingClaSignatures.headOption.fold {
              Future.successful(ClaSignature(-1, contact.gitHubId, LocalDateTime.now(), claVersion))
            } { existingClaSignature =>
              Future.failed(AlreadyExistsException(existingClaSignature))
            }
          } yield claSignature
        } else {
          Future.failed(new IllegalStateException("The CLA was not agreed to."))
        }
    }

    maybeClaSignatureFuture.fold {
      Future.successful(BadRequest("A required field was not specified."))
    } { claSignatureFuture =>
      for {
        claSignature <- claSignatureFuture
        persistedClaSignature <- db.createClaSignature(claSignature)
      } yield {
        revalidatePullRequests(claSignature.contactGitHubId).failed.foreach { e =>
          Logger.error("Could not revalidate PRs", e)
        }

        Redirect(routes.Application.signedCla())
      }
    } recover {
      case AlreadyExistsException(claSignature) =>
        BadRequest(claAlreadySignedView(claSignature.signedOn))
      case e: Throwable =>
        Logger.error("CLA could not be signed.", e)
        val baseErrorMessage = "Could not sign the CLA"
        val errorMessage = maybeOrgEmail.fold(baseErrorMessage) { orgEmail =>
          baseErrorMessage + ", please contact" + orgEmail
        }
        InternalServerError(errorMessage)
    }

  }

  def signedCla = Action {
    Ok(claSignedView())
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
      val maybeEvent = request.headers.get("X-GitHub-Event")

      if (maybeEvent.contains("pull_request")) {

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
          case "opened" | "reopened" | "synchronize" =>
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

  def status(org: String, repo: String, prNum: Int) = Action.async { implicit request =>
    getGitHubAuthInfo(request).flatMap { maybeGitHubAuthInfo =>
      gitHub.integrationInstallations().flatMap { installations =>
        val maybeOrgInstall = installations.as[Seq[JsObject]].find { repoJson =>
          val login = (repoJson \ "account" \ "login").as[String]
          login == org
        }

        maybeOrgInstall.fold(Future.successful(BadRequest("GitHub App not installed on that org"))) { orgInstall =>
          val id = (orgInstall \ "id").as[Int]
          gitHub.installationAccessTokens(id).flatMap { installationAccessTokenJson =>
            val installationAccessToken = (installationAccessTokenJson \ "token").as[String]

            gitHub.installationRepositories(installationAccessToken).flatMap { installationRepositories =>

              val maybeRepo = installationRepositories.value.find { repoJson =>
                (repoJson \ "name").asOpt[String].contains(repo)
              }

              maybeRepo.fold(Future.successful(BadRequest("GitHub App not enabled on that repo"))) { repoJson =>
                val fullName = (repoJson \ "full_name").as[String]
                val isPrivate = (repoJson \ "private").as[Boolean]

                def renderStatus(accessToken: String): Future[Result] = {
                  gitHub.getPullRequest(fullName, prNum, accessToken).flatMap { pullRequest =>
                    gitHub.pullRequestWithCommitsAndStatus(accessToken)(pullRequest).flatMap { pullRequestDetails =>
                      validatePullRequest(pullRequestDetails, accessToken).map {
                        case (_, claMissing, _) =>
                          val claUrl = routes.Application.signCla().absoluteURL()
                          Ok(claStatusView(org, repo, prNum, claMissing, claUrl))
                      }
                    }
                  }
                }

                if (isPrivate) {
                  maybeGitHubAuthInfo.fold {
                    Future.successful(
                      Redirect(
                        gitHubAuthUrl(gitHubOauthScopesForStatus, routes.Application.status(org, repo, prNum).absoluteURL())
                      )
                    )
                  } { gitHubAuthInfo =>
                    val userAccessToken = crypto.decryptAES(gitHubAuthInfo.encAuthToken)
                    renderStatus(userAccessToken)
                  }
                }
                else {
                 renderStatus(installationAccessToken)
                }
              }
            }
          }
        }
      }
    }
  }

  def audit = Action.async { implicit request =>
    getGitHubAuthInfo(request).flatMap { maybeGitHubAuthInfo =>
      maybeGitHubAuthInfo.fold {
        Future.successful(Redirect(gitHubAuthUrl(gitHubOauthScopesForAudit, routes.Application.audit().absoluteURL())))
      } { gitHubAuthInfo =>
        val userAccessToken = crypto.decryptAES(gitHubAuthInfo.encAuthToken)

        gitHub.integrationAndUserOrgs(userAccessToken).map { orgs =>
          val orgsWithEncAccessToken = orgs.mapValues(crypto.encryptAES)
          Ok(auditView(orgsWithEncAccessToken, gitHub.integrationSlug, gitHub.clientId))
        } recover {
          case e: IncorrectResponseStatus =>
            // user likely didn't have the right scope
            Ok(auditError(gitHub.clientId, e.message, gitHubAuthUrl(gitHubOauthScopesForAudit, routes.Application.audit().absoluteURL())))
        }
      }
    }
  }

  def auditContributors(org: String, ownerRepo: String, encAccessToken: String) = Action.async { implicit request =>
    val accessToken = crypto.decryptAES(encAccessToken)

    val collaboratorsFuture = gitHub.collaborators(ownerRepo, accessToken)
    val allContributorsFuture = gitHub.repoContributors(ownerRepo, accessToken)

    for {
      collaborators <- collaboratorsFuture
      allContributors <- allContributorsFuture.map(gitHub.contributorLoginsAndContributions)
      externalContributors = gitHub.externalContributors(allContributors.keySet, collaborators)
      gitHubUsers = externalContributors.collect { case gitHubUser: GitHub.GitHubUser => gitHubUser }
      clasForExternalContributors <- db.findClaSignaturesByGitHubIds(gitHubUsers)
    } yield {

      val externalContributorsDetails = externalContributors.map { gitHubId =>
        val maybeClaSignature = clasForExternalContributors.find(_.contactGitHubId == gitHubId)
        val commits = allContributors.getOrElse(gitHubId, 0)
        gitHubId -> (maybeClaSignature, commits)
      }.toMap

      val internalContributorsWithCommits = collaborators.flatMap { contributor =>
        val maybeContributions = allContributors.get(contributor)
        maybeContributions.fold(Set.empty[(GitHub.Contributor, Int)]) { contributions =>
          Set(contributor -> contributions)
        }
      }.toMap

      Ok(views.html.auditRepo(externalContributorsDetails, internalContributorsWithCommits))
    }
  }

  def auditRepos(org: String, encAccessToken: String) = Action.async { implicit request =>
    val accessToken = crypto.decryptAES(encAccessToken)

    gitHub.orgRepos(org, accessToken).map { jsArray =>
      val repos = jsArray.as[Seq[GitHub.Repo]]
      Ok(auditReposView(org, repos, encAccessToken))
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

  private def getGitHubAuthInfo(request: RequestHeader): Future[Option[GitHubAuthInfo]] = {
    request.flash.get("encAccessToken").fold {
      Future.successful[Option[GitHubAuthInfo]](None)
    } { encAccessToken =>
      val accessToken = crypto.decryptAES(encAccessToken)
      gitHub.userInfo(accessToken).map { userInfo =>
        val username = (userInfo \ "login").as[String]
        val maybeFullName = (userInfo \ "name").asOpt[String]
        val maybeEmail = (userInfo \ "email").asOpt[String]
        Some(GitHubAuthInfo(encAccessToken, username, maybeFullName, maybeEmail))
      }
    }
  }

  private def validatePullRequest(pullRequestToBeValidated: JsObject, token: String)(implicit request: RequestHeader): Future[(Set[GitHub.Contributor], Set[GitHub.Contributor], JsObject)] = {
    val claUrl = routes.Application.signCla().absoluteURL()
    val statusUrl = (owner: String, repo: String, prNum: Int) => routes.Application.status(owner, repo, prNum).absoluteURL()
    gitHub.validatePullRequest(pullRequestToBeValidated, token, claUrl, statusUrl) { externalCommitters =>
      val gitHubUsers = externalCommitters.collect { case gitHubUser: GitHub.GitHubUser => gitHubUser }
      db.findClaSignaturesByGitHubIds(gitHubUsers)
    }
  }

  private def validatePullRequests(pullRequestsToBeValidated: Map[JsObject, String])(implicit request: RequestHeader): Future[Iterable[(Set[GitHub.Contributor], Set[GitHub.Contributor], JsObject)]] = {
    val claUrl = routes.Application.signCla().absoluteURL()
    val statusUrl = (owner: String, repo: String, prNum: Int) => routes.Application.status(owner, repo, prNum).absoluteURL()
    gitHub.validatePullRequests(pullRequestsToBeValidated, claUrl, statusUrl) { externalCommitters =>
      val gitHubUsers = externalCommitters.collect { case gitHubUser: GitHub.GitHubUser => gitHubUser }
      db.findClaSignaturesByGitHubIds(gitHubUsers)
    }
  }

  // When someone signs the CLA we don't know what PR we need to update.
  // So get all the PRs we have access to, that have the contributor which just signed the CLA and where the status is failed.
  private def revalidatePullRequests(signerGitHubId: String)(implicit request: RequestHeader): Future[Iterable[(Set[GitHub.Contributor], Set[GitHub.Contributor], JsObject)]] = {
    for {
      pullRequestsToBeValidated <- gitHub.pullRequestsToBeValidated(signerGitHubId)
      validation <- validatePullRequests(pullRequestsToBeValidated)
    } yield validation
  }

  private def gitHubAuthUrl(scopes: Seq[String], state: String)(implicit request: RequestHeader): String = {
    val query = Query("client_id" -> gitHub.clientId, "redirect_uri" -> redirectUri, "scope" -> scopes.mkString(" "), "state" -> state)
    val uri = Uri("https://gitHub.com/login/oauth/authorize")

    uri.withQuery(query).toString()
  }

  private def redirectUri(implicit request: RequestHeader): String = {
    routes.Application.gitHubOauthCallback("", "").absoluteURL(request.secure).stripSuffix("?code=&state=")
  }

  case class AlreadyExistsException(claSignature: ClaSignature) extends Exception

}
