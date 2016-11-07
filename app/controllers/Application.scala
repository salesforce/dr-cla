package controllers

import javax.inject.Inject

import models._
import modules.Database
import org.joda.time.LocalDateTime
import play.api.{Environment, Logger}
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.mvc._
import utils.{Crypto, GitHub}

import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source


class Application @Inject() (env: Environment, gitHub: GitHub, db: Database, crypto: Crypto) (implicit staticWebJarAssets: StaticWebJarAssets, ec: ExecutionContext) extends Controller {

  val claVersions = Set("0.0")
  val latestClaVersion = claVersions.head

  val gitHubOauthScopesForClaSigning = Seq("user","user:email")
  val gitHubOauthScopesForAudit = Seq("read:org")

  def wellKnown = Action {
    Ok("u7z9WBJjcmsSQ-85QrlETUoPQi8FXTDbe5_8sv-voow.Xuyp9lLbA7MdhO4SM9lxZNpSdIE51haMPkZe962uin0")
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
          revalidatePullRequests(claSignature.contact.gitHubId).onFailure {
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

  def webhookPullRequest = Action.async(parse.json) { implicit request =>
    (request.body \ "pull_request").asOpt[JsValue].fold {
      // the webhook call didn't have a pull request - it was likely a test hook
      (request.body \ "zen").asOpt[String].fold {
        Future.successful(BadRequest("Was this a test?  If so, where is your zen?"))
      } { zen =>
        Future.successful(Ok(zen))
      }
    } { pullRequest =>
      val state = (pullRequest \ "state").as[String]
      state match {
        case "closed" =>
          Future.successful(Ok)
        case "open" =>
          for {
            pullRequestWithCommitsAndStatus <- gitHub.pullRequestWithCommitsAndStatus(gitHub.integrationToken)(pullRequest)
            validate <- validatePullRequests(Seq(pullRequestWithCommitsAndStatus))
          } yield Ok
      }
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

  private def validatePullRequests(pullRequests: Seq[JsObject])(implicit request: RequestHeader): Future[JsArray] = {
    val claUrl = routes.Application.signCla().absoluteURL()

    gitHub.userInfo(gitHub.integrationToken).flatMap { integrationUser =>
      val integrationUserId = (integrationUser \ "login").as[String]

      val results = pullRequests.map { pullRequest =>
        val ownerRepo = (pullRequest \ "pull_request" \ "base" \ "repo" \ "full_name").as[String]
        val prNumber = (pullRequest \ "pull_request" \ "number").as[Int]
        val sha = (pullRequest \ "pull_request" \ "head" \ "sha").as[String]

        // update the PR status to pending
        val prPendingFuture = gitHub.createStatus(ownerRepo, sha, "pending", claUrl, "The CLA verifier is running", "salesforce-cla", gitHub.integrationToken)

        val prCommitsFuture = gitHub.pullRequestCommits(ownerRepo, prNumber, gitHub.integrationToken)

        val prCommittersFuture = prCommitsFuture.map { commits =>
          commits.value.map(_.\("author").\("login").as[String])
        }

        val existingCommittersFuture = gitHub.collaborators(ownerRepo, gitHub.integrationToken).map(_.value.map(_.\("login").as[String]))

        val externalCommittersFuture = for {
          _ <- prPendingFuture
          prCommitters <- prCommittersFuture
          existingCommitters <- existingCommittersFuture
        } yield prCommitters.filterNot(existingCommitters.contains).toSet

        val committersWithoutClasFuture = for {
          externalCommitters <- externalCommittersFuture
          clasForCommitters <- db.query(GetClaSignatures(externalCommitters))
        } yield {
          // todo: maybe check latest CLA version
          externalCommitters.filterNot(clasForCommitters.map(_.contact.gitHubId).contains)
        }

        val repoLabelsFuture = gitHub.getAllLabels(ownerRepo, gitHub.integrationToken).map(_.value.map(_.\("name").as[String]).distinct.toList)

        val labelMap = Map(("cla:missing","c40d0d"),("cla:signed","5ebc41"))

        val labelCreatesFuture: Future[Seq[JsValue]] = repoLabelsFuture.flatMap { labels =>
          val labelsToCreate: Seq[String] = labelMap.keys.toList.diff(labels)
          gitHub.createLabels(ownerRepo, labelMap.filterKeys(labelsToCreate.contains), gitHub.integrationToken)
        }

        committersWithoutClasFuture.flatMap { committersWithoutClas =>

          val state = if (committersWithoutClas.isEmpty) "success" else "failure"
          val description = if (committersWithoutClas.isEmpty) "All contributors have signed the CLA" else "One or more contributors need to sign the CLA"

          gitHub.createStatus(ownerRepo, sha, state, claUrl, description, "salesforce-cla", gitHub.integrationToken).flatMap { status =>

            // don't re-comment on the PR
            gitHub.issueComments(ownerRepo, prNumber, gitHub.integrationToken).flatMap { comments =>
              val alreadyCommented = comments.value.exists(_.\("user").\("login").as[String] == integrationUserId)

              if (committersWithoutClas.nonEmpty) {
                gitHub.toggleLabelSafe(ownerRepo, "cla:missing", "cla:signed", alreadyCommented, prNumber, gitHub.integrationToken)

                if(!alreadyCommented) {
                  val body = s"Thanks for the contribution!  Before we can merge this, we need ${committersWithoutClas.map(" @" + _).mkString} to [sign the Salesforce Contributor License Agreement]($claUrl)."
                  gitHub.commentOnIssue(ownerRepo, prNumber, body, gitHub.integrationToken)
                } else {
                  Future.successful(status)
                }
              }
              else {
                gitHub.toggleLabelSafe(ownerRepo, "cla:signed", "cla:missing", alreadyCommented, prNumber, gitHub.integrationToken)
                Future.successful(status)
              }
            }
          }
        }
      }

      Future.fold(results)(Json.arr())(_ :+ _)
    }
  }

  // todo: add some caching
  private def revalidatePullRequests(signerGitHubId: String)(implicit request: RequestHeader): Future[JsValue] = {
    for {
      pullRequestsToBeValidated <- gitHub.pullRequestsToBeValidated(signerGitHubId, gitHub.integrationToken)
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
