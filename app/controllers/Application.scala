package controllers

import java.util.{Date, UUID}
import javax.inject.Inject

import models._
import modules.Database
import play.api.Environment
import play.api.libs.Crypto
import play.api.libs.json.{JsArray, Json, JsObject, JsValue}
import play.api.mvc._
import utils.GitHub

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.io.Source


class Application @Inject() (env: Environment, gitHub: GitHub, db: Database) extends Controller {

  val claVersions = Set("0.0")
  val latestClaVersion = claVersions.head

  val gitHubOauthScopes = Seq("user","user:email")

  def githubOauthCallback(code: String) = Action.async { request =>
    gitHub.accessToken(code).flatMap { accessToken =>
      gitHub.userInfo(accessToken).map { userInfo =>
        val username = (userInfo \ "login").as[String]
        val maybeFullName = (userInfo \ "name").asOpt[String]
        val maybeEmail = (userInfo \ "email").asOpt[String]
        val encAccessToken = Crypto.encryptAES(accessToken)
        val gitHubAuthInfo = GitHubAuthInfo(encAccessToken, username, maybeFullName, maybeEmail)
        Ok(views.html.claSign(latestClaVersion, authUrl(request, gitHubOauthScopes), Some(gitHubAuthInfo), latestClaVersion, claText(latestClaVersion)))
      }
    } recover {
      case e: utils.UnauthorizedError => Redirect(routes.Application.signCla).flashing("error" -> e.getMessage)
      case e: Exception => InternalServerError(e.getMessage)
    }
  }

  def signCla = Action { request =>
    Ok(views.html.claSign(claVersions.head, authUrl(request, gitHubOauthScopes), None, latestClaVersion, claText(latestClaVersion)))
  }

  def submitCla = Action.async(parse.urlFormEncoded) { request =>
    val maybeClaSignatureFuture = for {
      encGitHubToken <- request.body.get("encGitHubToken").flatMap(_.headOption)
      claVersion <- claVersions.find(request.body.get("claVersion").flatMap(_.headOption).contains)
      fullName <- request.body.get("fullName").flatMap(_.headOption)
      email <- request.body.get("email").flatMap(_.headOption)
      agreeToCLA <- request.body.get("agreeToCLA").flatMap(_.headOption)
    } yield {
        if (agreeToCLA == "on") {
          val gitHubToken = Crypto.decryptAES(encGitHubToken)
          gitHub.userInfo(gitHubToken).map { userInfo =>
            val username = (userInfo \ "login").as[String]
            val (firstName, lastName) = Contact.fullNameToFirstAndLast(fullName)
            val contact = Contact(UUID.randomUUID().toString, firstName, lastName, email)
            ClaSignature(UUID.randomUUID().toString, contact, username, new Date(), claVersion)
          }
        } else {
          Future.failed(new IllegalStateException("The CLA was not agreed to."))
        }
    }

    maybeClaSignatureFuture.fold {
      Future.successful(BadRequest("A required field was not specified."))
    } { claSignatureFuture =>
      claSignatureFuture.flatMap { claSignature =>
        // todo: transaction?
        for {
          contactsCreated <- db.execute(CreateContact(claSignature.contact))
          if contactsCreated == 1
          claSignaturesCreated <- db.execute(CreateClaSignature(claSignature))
          if claSignaturesCreated == 1
          _ <- revalidatePullRequests(claSignature.gitHubId)(request) // todo: maybe do this off the request thread
        } yield Redirect(routes.Application.signedCla)
      }
    }

  }

  def signedCla = Action {
    Ok(views.html.claSigned())
  }

  def webhookPullRequest = Action.async(parse.json) { request =>
    // todo: maybe filter on "action" = "opened", "reopened”, or “synchronize”
    for {
      pullRequestWithCommitsAndStatus <- pullRequestWithCommitsAndStatus((request.body \ "pull_request").as[JsValue])
      validate <- validatePullRequests(Seq(pullRequestWithCommitsAndStatus))(request)
    } yield Ok
  }

  private def validatePullRequests(pullRequests: Seq[JsObject])(request: RequestHeader): Future[JsArray] = {
    val claUrl = routes.Application.signCla().absoluteURL()(request)

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
          commits.value.map(_.\("committer").\("login").as[String])
        }

        val internalCommittersFuture = gitHub.collaborators(ownerRepo, gitHub.integrationToken).map(_.value.map(_.\("login").as[String]))

        val externalCommittersFuture = for {
          _ <- prPendingFuture
          prCommitters <- prCommittersFuture
          internalCommitters <- internalCommittersFuture
        } yield prCommitters.filterNot(internalCommitters.contains).toSet

        val committersWithoutClasFuture = for {
          externalCommitters <- externalCommittersFuture
          clasForCommitters <- db.query(GetClaSignatures(externalCommitters))
        } yield {
          // todo: maybe check latest CLA version
          externalCommitters.filterNot(clasForCommitters.map(_.gitHubId).contains)
        }

        committersWithoutClasFuture.flatMap { committersWithoutClas =>
          val state = if (committersWithoutClas.isEmpty) "success" else "failure"
          val description = if (committersWithoutClas.isEmpty) "All contributors have signed the CLA" else "One or more contributors need to sign the CLA"

          gitHub.createStatus(ownerRepo, sha, state, claUrl, description, "salesforce-cla", gitHub.integrationToken).flatMap { status =>
            // don't re-comment on the PR
            gitHub.issueComments(ownerRepo, prNumber, gitHub.integrationToken).flatMap { comments =>
              val alreadyCommented = comments.value.exists(_.\("user").\("login").as[String] == integrationUserId)

              if (committersWithoutClas.nonEmpty && !alreadyCommented) {
                val body = s"Thanks for the contribution!  Before we can merge this, we need ${committersWithoutClas.map(" @" + _).mkString} to [sign the Salesforce Contributor License Agreement]($claUrl)."
                gitHub.commentOnIssue(ownerRepo, prNumber, body, gitHub.integrationToken)
              }
              else {
                Future.successful(status)
              }
            }
          }
        }
      }

      Future.fold(results)(Json.arr())(_ :+ _)
    }
  }

  private def pullRequestWithCommitsAndStatus(pullRequest: JsValue): Future[JsObject] = {
    val ownerRepo = (pullRequest \ "base" \ "repo" \ "full_name").as[String]
    val prId = (pullRequest \ "number").as[Int]
    val ref = (pullRequest \ "head" \ "sha").as[String]
    val commitsFuture = gitHub.pullRequestCommits(ownerRepo, prId, gitHub.integrationToken)
    val statusFuture = gitHub.commitStatus(ownerRepo, ref, gitHub.integrationToken)
    for {
      commits <- commitsFuture
      status <- statusFuture
    } yield {
      // combine the pull request, commits, and status into one json object
      Json.obj(
        "pull_request" -> pullRequest,
        "commits" -> commits,
        "status" -> status
      )
    }
  }

  private def pullRequestHasContributorAndState(contributorId: String, state: String)(pullRequest: JsObject): Boolean = {
    val contributors = (pullRequest \ "commits").as[JsArray].value.map(_.\("author").\("login").as[String])
    val prState = (pullRequest \ "status" \ "state").as[String]
    contributors.contains(contributorId) && (state == prState)
  }

  // todo: add some caching
  private def revalidatePullRequests(signerGitHubId: String)(request: RequestHeader): Future[JsValue] = {
    for {
      repos <- gitHub.allRepos(gitHub.integrationToken)
      repoNames = repos.value.map(_.\("full_name").as[String])
      allPullRequests <- Future.sequence(repoNames.map(ownerRepo => gitHub.pullRequests(ownerRepo, gitHub.integrationToken)))
      pullRequestWithCommitsAndStatus <- Future.sequence(allPullRequests.flatMap(_.value).map(pullRequestWithCommitsAndStatus))
      pullRequestsToBeValidated = pullRequestWithCommitsAndStatus.filter(pullRequestHasContributorAndState(signerGitHubId, "failure"))
      validation <- validatePullRequests(pullRequestsToBeValidated)(request)
    } yield validation
  }

  private def authUrl(implicit request: RequestHeader, scopes: Seq[String]): String = {
    s"https://github.com/login/oauth/authorize?client_id=${gitHub.clientId}&redirect_uri=$redirectUri&scope=${scopes.mkString(",")}"
  }

  private def redirectUri(implicit request: RequestHeader): String = {
    routes.Application.githubOauthCallback("").absoluteURL(request.secure).stripSuffix("?code=")
  }

  private def claText(version: String): String = {
    val claPath = s"clas/icla-$version.html"
    val claTextInputStream = env.resourceAsStream(claPath).getOrElse(throw new IllegalStateException(s"Could not locate the CLA: $claPath"))
    val claText = Source.fromInputStream(claTextInputStream).mkString
    claTextInputStream.close()
    claText
  }

}
