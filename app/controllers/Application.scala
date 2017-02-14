package controllers

import javax.inject.Inject

import models._
import modules.Database
import org.apache.commons.codec.digest.HmacUtils
import org.joda.time.LocalDateTime
import play.api.{Configuration, Environment, Logger}
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.mvc.Results.EmptyContent
import play.api.mvc._
import utils.GitHub.AuthorLoginNotFound
import utils.{Crypto, GitHub}

import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source


class Application @Inject() (env: Environment, gitHub: GitHub, db: Database, crypto: Crypto, configuration: Configuration) (implicit staticWebJarAssets: StaticWebJarAssets, ec: ExecutionContext) extends Controller {

  val claVersions = Set("0.0")
  val latestClaVersion = claVersions.head

  val gitHubOauthScopesForClaSigning = Seq("user:email")
  val gitHubOauthScopesForAudit = Seq("read:org")

  def wellKnown(key: String) = Action {
    configuration.getString("wellknown").fold(NotFound(EmptyContent())) { wellKnownKeyValue =>
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
    getGitHubAuthInfo(request).map { maybeGitHubAuthInfo =>
      val authUrl = gitHubAuthUrl(gitHubOauthScopesForClaSigning, routes.Application.signCla().absoluteURL())
      Ok(views.html.claSign(latestClaVersion, authUrl, maybeGitHubAuthInfo, latestClaVersion, claText(latestClaVersion)))
    }
  }

  def submitCla = Action.async(parse.urlFormEncoded) { implicit request =>
    val maybeClaSignatureFuture = for {
      encGitHubToken <- request.body.get("encGitHubToken").flatMap(_.headOption)
      claVersion <- claVersions.find(request.body.get("claVersion").flatMap(_.headOption).contains)
      fullName <- request.body.get("fullName").flatMap(_.headOption)
      email <- request.body.get("email").flatMap(_.headOption)
      agreeToCLA <- request.body.get("agreeToCLA").flatMap(_.headOption)
    } yield {
        if (agreeToCLA == "on") {
          val gitHubToken = crypto.decryptAES(encGitHubToken)

          for {
            userInfo <- gitHub.userInfo(gitHubToken)
            username = (userInfo \ "login").as[String]
            maybeContact <- db.query(GetContactByGitHubId(username))
            contact = maybeContact.getOrElse {
              val (firstName, lastName) = Contact.fullNameToFirstAndLast(fullName)
              Contact(-1, firstName, lastName, email, username)
            }
          } yield ClaSignature(-1, contact, new LocalDateTime(), claVersion)
        } else {
          Future.failed(new IllegalStateException("The CLA was not agreed to."))
        }
    }

    maybeClaSignatureFuture.fold {
      Future.successful(BadRequest("A required field was not specified."))
    } { claSignatureFuture =>
      claSignatureFuture.flatMap { claSignature =>
        // todo: transaction?

        val createContactIfNeededFuture = if (claSignature.contact.id == -1) {
          db.execute(CreateContact(claSignature.contact))
        } else {
          Future.successful(0)
        }

        for {
          contactsCreated <- createContactIfNeededFuture
          claSignaturesCreated <- db.execute(CreateClaSignature(claSignature))
          if claSignaturesCreated == 1
        } yield {
          // todo: support integrations
          revalidatePullRequests(claSignature.contact.gitHubId, gitHub.integrationToken).onFailure {
            case e: Exception => Logger.error("Could not revalidate PRs", e)
          }
          Redirect(routes.Application.signedCla())
        }
      }
    } recover {
      case _ =>
        Logger.error("CLA could not be signed. " + request.body.toString())
        InternalServerError("Could not sign the CLA, please contact oss-cla@salesforce.com")
    }

  }

  def signedCla = Action {
    Ok(views.html.claSigned.apply)
  }

  case class NoPullRequest() extends Exception {
    override def getMessage: String = "A pull request could not be found"
  }

  private def handlePullRequest(jsValue: JsValue, token: String)(implicit request: RequestHeader): Future[JsArray] = {
    (jsValue \ "pull_request").asOpt[JsValue].fold(Future.failed[JsArray](NoPullRequest())) { pullRequest =>
      val state = (pullRequest \ "state").as[String]
      val userType = (pullRequest \ "user" \ "type").as[String]
      (state, userType) match {
        // Only run the validator for open pull requests where the user is a user (i.e. not a bot)
        case ("open", "User") =>
          for {
            pullRequestWithCommitsAndStatus <- gitHub.pullRequestWithCommitsAndStatus(token)(pullRequest)
            validate <- validatePullRequests(Map(pullRequestWithCommitsAndStatus -> token))
          } yield validate
        case _ =>
          Future.successful(JsArray())
      }
    }
  }

  def webhookPullRequest = Action.async(parse.json) { implicit request =>
    handlePullRequest(request.body, gitHub.integrationToken).map(_ => Ok).recover {
      case e: NoPullRequest =>
        (request.body \ "zen").asOpt[String].fold {
          BadRequest("Was this a test?  If so, where is your zen?")
        } { zen =>
          Ok(zen)
        }
      case e: Exception =>
        Logger.error("Error handling pull request", e)
        InternalServerError(e.getMessage)
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
          case "opened" | "reopened" =>
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

  def audit = Action.async { implicit request =>
    getGitHubAuthInfo(request).flatMap { maybeGitHubAuthInfo =>
      maybeGitHubAuthInfo.fold {
        Future.successful(Redirect(gitHubAuthUrl(gitHubOauthScopesForAudit, routes.Application.audit().absoluteURL())))
      } { gitHubAuthInfo =>
        val accessToken = crypto.decryptAES(gitHubAuthInfo.encAuthToken)

        val userAndIntegrationOrgsFuture = for {
          // the user must be either a member or an admin
          userOrgs <- gitHub.userMembershipOrgs(Some("active"), accessToken).map(orgsWithRole(Seq("admin")))
          // the integration user must be an admin
          integrationOrgs <- gitHub.userMembershipOrgs(Some("active"), gitHub.integrationToken).map(orgsWithRole(Seq("admin")))
          systemUser <- gitHub.userInfo(gitHub.integrationToken).map(_.\("login").as[String])
        } yield (userOrgs.intersect(integrationOrgs), systemUser)

        userAndIntegrationOrgsFuture.map { case (orgs, systemUser) =>
          Ok(views.html.audit(gitHubAuthInfo.encAuthToken, orgs, systemUser))
        }
      }
    }
  }

  private def orgsWithRole(roles: Seq[String])(jsArray: JsArray): Seq[GitHub.Org] = {
    jsArray.value.filter(org => roles.contains(org.\("role").as[String])).map(_.as[GitHub.Org])
  }

  private def isOrgAdmin(org: String, accessToken: String): Future[Boolean] = {
    gitHub.userMembershipOrgs(Some("active"), accessToken).map { jsArray =>
      val orgs = orgsWithRole(Seq("admin"))(jsArray)
      orgs.exists(_.login == org)
    }
  }

  def auditPrValidatorStatus(org: String, encAccessToken: String) = Action.async { implicit request =>
    val accessToken = crypto.decryptAES(encAccessToken)

    isOrgAdmin(org, accessToken).flatMap { isOrgAdmin =>
      if (isOrgAdmin) {
        gitHub.userOrgMembership(org, accessToken).flatMap { orgInfo =>
          val isAdmin = (orgInfo \ "role").as[String] == "admin"
          gitHub.orgWebhooks(org, gitHub.integrationToken).map { webhooks =>
            val webhookUrl = routes.Application.webhookPullRequest().absoluteURL()
            val maybeWebhook = webhooks.value.find(_.\("config").\("url").as[String] == webhookUrl)
            val maybeWebhookUrl = maybeWebhook.map { webhook =>
              val id = (webhook \ "id").as[Int]
              s"https://github.com/organizations/$org/settings/hooks/$id"
            }
            Ok(views.html.prValidatorStatus(org, maybeWebhookUrl, isAdmin, encAccessToken))
          } recover {
            case e: Exception => InternalServerError("Could not fetch the org's Webhooks")
          }
        }
      }
      else {
        Future.successful(Unauthorized("You are not authorized to access this org"))
      }
    }
  }

  def auditContributors(org: String, ownerRepo: String, encAccessToken: String) = Action.async { implicit request =>
    val accessToken = crypto.decryptAES(encAccessToken)

    // maybe this should be repo collaborators instead?
    val internalContributorsFuture = gitHub.orgMembers(org, accessToken)

    val repoCommitsFuture = gitHub.repoCommits(ownerRepo, accessToken)

    for {
      internalContributors <- internalContributorsFuture.map(_.value.map(_.\("login").as[String]).distinct.toSet)
      repoCommits <- repoCommitsFuture
      authors = repoCommits.value.map(gitHub.commitAuthor).distinct.toSet
      externalContributors = authors.diff(internalContributors)
      clasForExternalContributors <- db.query(GetClaSignatures(externalContributors))
    } yield {

      val externalContributorsDetails = externalContributors.map { gitHubId =>
        val maybeClaSignature = clasForExternalContributors.find(_.contact.gitHubId == gitHubId)
        val commits = repoCommits.value.filter(gitHub.commitAuthor(_) == gitHubId)
        gitHubId -> (maybeClaSignature, commits)
      }.toMap

      val internalContributorsDetails = internalContributors.map { gitHubId =>
        val commits = repoCommits.value.filter(gitHub.commitAuthor(_) == gitHubId)
        gitHubId -> commits
      }.toMap.filter { case (_, commits) =>
        commits.nonEmpty
      }

      Ok(views.html.auditRepo(externalContributorsDetails, internalContributorsDetails))
    }
  }

  def auditRepos(org: String, encAccessToken: String) = Action.async { implicit request =>
    val accessToken = crypto.decryptAES(encAccessToken)

    gitHub.orgRepos(org, accessToken).map { jsArray =>
      val repos = jsArray.as[Seq[GitHub.Repo]]
      Ok(views.html.auditRepos(org, repos, encAccessToken))
    }
  }

  def addPrValidatorWebhook() = Action.async(parse.urlFormEncoded) { implicit request =>
    val maybeOrg = request.body.get("org").flatMap(_.headOption)
    val maybeEncAccessToken = request.body.get("encAccessToken").flatMap(_.headOption)

    (maybeOrg, maybeEncAccessToken) match {
      case (Some(org), Some(encAccessToken)) =>
        val accessToken = crypto.decryptAES(encAccessToken)

        isOrgAdmin(org, accessToken).flatMap { isOrgAdmin =>
          if (isOrgAdmin) {
            val webhookUrl = routes.Application.webhookPullRequest().absoluteURL()
            gitHub.addOrgWebhook(org, Seq("pull_request"), webhookUrl, "json", gitHub.integrationToken).map { _ =>
              Redirect(routes.Application.audit())
            }
          }
          else {
            Future.successful(Unauthorized("You do not have admin or member access to this org"))
          }
        }
      case _ =>
        Future.successful(BadRequest("Required fields missing"))
    }
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

  private def validatePullRequests(pullRequestsToBeValidated: Map[JsObject, String])(implicit request: RequestHeader): Future[JsArray] = {
    val claUrl = routes.Application.signCla().absoluteURL()
    gitHub.validatePullRequests(pullRequestsToBeValidated, claUrl) { externalCommitters =>
      db.query(GetClaSignatures(externalCommitters))
    }
  }

  // When someone signs the CLA we don't know what PR we need to update.
  // So get all the PRs we have access to, that have the contributor which just signed the CLA and where the status is failed.
  private def revalidatePullRequests(signerGitHubId: String, token: String)(implicit request: RequestHeader): Future[JsValue] = {
    for {
      pullRequestsToBeValidated <- gitHub.pullRequestsToBeValidated(signerGitHubId, token)
      validation <- validatePullRequests(pullRequestsToBeValidated)
    } yield validation
  }

  private def gitHubAuthUrl(scopes: Seq[String], state: String)(implicit request: RequestHeader): String = {
    s"https://gitHub.com/login/oauth/authorize?client_id=${gitHub.clientId}&redirect_uri=$redirectUri&scope=${scopes.mkString(",")}&state=$state"
  }

  private def redirectUri(implicit request: RequestHeader): String = {
    routes.Application.gitHubOauthCallback("", "").absoluteURL(request.secure).stripSuffix("?code=&state=")
  }

  private def claText(version: String): String = {
    val claPath = s"clas/icla-$version.html"
    val claTextInputStream = env.resourceAsStream(claPath).getOrElse(throw new IllegalStateException(s"Could not locate the CLA: $claPath"))
    val claText = Source.fromInputStream(claTextInputStream).mkString
    claTextInputStream.close()
    claText
  }

}
