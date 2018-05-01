/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import java.io.StringReader
import java.net.{URI, URL}
import java.security.KeyPair
import java.util.Locale

import javax.inject.Inject
import models.ClaSignature
import org.apache.commons.codec.binary.Base64
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.{PEMKeyPair, PEMParser}
import pdi.jwt.JwtJson._
import pdi.jwt.{JwtAlgorithm, JwtClaim, JwtJson}
import play.api.Configuration
import play.api.http.{HeaderNames, HttpVerbs, MimeTypes, Status}
import play.api.i18n.{Lang, MessagesApi}
import play.api.libs.json.Reads._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.ws.{WSClient, WSRequest, WSResponse}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions
import scala.util.Try

class GitHub @Inject() (configuration: Configuration, ws: WSClient, messagesApi: MessagesApi) (implicit ec: ExecutionContext) {

  import GitHub._

  implicit val defaultLang: Lang = Lang(Locale.getDefault)

  val clientId = configuration.get[String]("github.oauth.client-id")
  val clientSecret = configuration.get[String]("github.oauth.client-secret")

  val integrationId = configuration.get[String]("github.integration.id")
  val integrationSlug = configuration.get[String]("github.integration.slug")

  val integrationClientId = configuration.get[String]("github.integration.client-id")
  val integrationClientSecret = configuration.get[String]("github.integration.client-secret")

  val gitHubBotName = configuration.get[String]("github.botname")
  val orgName = configuration.get[String]("app.organization.name")

  val integrationKeyPair: KeyPair = {
    val privateKeyString = configuration.get[String]("github.integration.private-key")

    val stringReader = new StringReader(privateKeyString)

    val pemParser = new PEMParser(stringReader)

    val pemObject = pemParser.readObject()

    new JcaPEMKeyConverter().getKeyPair(pemObject.asInstanceOf[PEMKeyPair])
  }

  val maybeIntegrationSecretToken = configuration.getOptional[String]("github.integration.secret-token")

  val labels: Map[String, String] = Map(("cla:missing", "c40d0d"), ("cla:signed", "5ebc41"))

  def ws(path: String, accessToken: String): WSRequest = {
    ws
      .url(s"https://api.github.com/$path")
      .withHttpHeaders(
        HeaderNames.AUTHORIZATION -> s"token $accessToken",
        HeaderNames.ACCEPT -> "application/vnd.github.machine-man-preview+json"
      )
  }

  def accessToken(code: String, oauthClientId: String, oauthClientSecret: String): Future[String] = {
    val wsFuture = ws.url("https://github.com/login/oauth/access_token").withQueryStringParameters(
      "client_id" -> oauthClientId,
      "client_secret" -> oauthClientSecret,
      "code" -> code
    ).withHttpHeaders(HeaderNames.ACCEPT -> MimeTypes.JSON).execute(HttpVerbs.POST)

    wsFuture.flatMap { response =>
      (response.json \ "access_token").asOpt[String].fold {
        val maybeError = (response.json \ "error_description").asOpt[String]
        Future.failed[String](UnauthorizedError(maybeError.getOrElse(response.body)))
      } {
        Future.successful
      }
    }
  }

  def jwtEncode(claim: JwtClaim): String = {
    JwtJson.encode(claim, integrationKeyPair.getPrivate, JwtAlgorithm.RS256)
  }

  def jwtWs(path: String): (WSRequest, JwtClaim) = {
    val claim = JwtClaim(issuer = Some(this.integrationId)).issuedNow.expiresIn(60)
    val jwt = jwtEncode(claim)

    val wsRequest = ws.url(s"https://api.github.com/$path")
      .withHttpHeaders(
        HeaderNames.AUTHORIZATION -> s"Bearer $jwt",
        HeaderNames.ACCEPT -> "application/vnd.github.machine-man-preview+json"
      )

    (wsRequest, claim)
  }

  def installationAccessTokens(installationId: Int): Future[JsValue] = {
    val (ws, claim) = jwtWs(s"installations/$installationId/access_tokens")

    ws.post(claim.toJsValue()).flatMap(created)
  }

  private def fetchPages(path: String, accessToken: String, pageSize: Int = 100): Future[JsArray] = {
    fetchPagesWithExtractor(path, accessToken, pageSize)(_.as[JsArray])
  }

  private def fetchPagesWithExtractor(path: String, accessToken: String, pageSize: Int = 100)(extractor: JsValue => JsArray): Future[JsArray] = {
    import io.netty.handler.codec.http.QueryStringDecoder

    import collection.JavaConverters._

    implicit class Regex(sc: StringContext) {
      def r = new scala.util.matching.Regex(sc.parts.mkString, sc.parts.tail.map(_ => "x"): _*)
    }

    def req(path: String, accessToken: String, page: Int, pageSize: Int): Future[WSResponse] = {
      ws(path, accessToken).withQueryStringParameters("page" -> page.toString, "per_page" -> pageSize.toString).get().flatMap(ok)
    }

    // get the first page
    req(path, accessToken, 1, pageSize).flatMap { response =>
      val firstPage = extractor(response.json)

      def urlToPage(urlString: String): Int = {
        val url = new URL(urlString)
        val params = new QueryStringDecoder(url.toURI.getRawQuery, false).parameters.asScala.mapValues(_.asScala.toSeq).toMap
        params("page").head.toInt
      }

      val pages = response.header("Link") match {
        case Some(r"""<(.*)$n>; rel="next", <(.*)$l>; rel="last"""") =>
          Range(urlToPage(n), urlToPage(l)).inclusive
        case _ =>
          Range(0, 0)
      }

      val pagesFutures = pages.map(req(path, accessToken, _, pageSize).map(response => extractor(response.json)))

      // assume numeric paging so we can parallelize
      Future.foldLeft(pagesFutures)(firstPage)(_ ++ _)
    }
  }

  // deals with pagination
  private def userOrOrgRepos(userOrOrg: Either[String, String], accessToken: String, pageSize: Int): Future[JsArray] = {

    val path = userOrOrg match {
      case Left(_) => s"user/repos"
      case Right(org) => s"orgs/$org/repos"
    }

    fetchPages(path, accessToken)
  }

  def userRepos(user: String, accessToken: String, pageSize: Int = 100): Future[JsArray] = {
    userOrOrgRepos(Left(user), accessToken, pageSize)
  }

  def orgRepos(org: String, accessToken: String, pageSize: Int = 100): Future[JsArray] = {
    userOrOrgRepos(Right(org), accessToken, pageSize)
  }

  def allRepos(accessToken: String): Future[JsArray] = {
    for {
      userInfo <- userInfo(accessToken)
      userLogin = (userInfo \ "login").as[String]
      userOrgs <- userOrgs(accessToken)
      orgNames = userOrgs.value.map(_.\("login").as[String])
      userRepos <- userRepos(userLogin, accessToken)
      orgsRepos <- Future.sequence(orgNames.map(org => orgRepos(org, accessToken)))
    } yield {
      orgsRepos.fold(userRepos) { case (allRepos, orgRepo) =>
        allRepos ++ orgRepo
      }
    }
  }

  def userOrgs(accessToken: String): Future[JsArray] = {
    ws("user/orgs", accessToken).get().flatMap(okT[JsArray])
  }

  def userMembershipOrgs(maybeState: Option[String], accessToken: String): Future[JsArray] = {
    val maybeParams = maybeState.map(state => Seq("state" -> state)).getOrElse(Seq.empty[(String, String)])
    ws("user/memberships/orgs", accessToken).withQueryStringParameters(maybeParams:_*).get().flatMap(okT[JsArray])
  }

  def userInfo(accessToken: String): Future[JsValue] = {
    ws("user", accessToken).get().flatMap { response =>
      response.status match {
        case Status.OK => Future.successful(response.json)
        case _ => Future.failed(new Exception(response.body))
      }
    }
  }

  def getPullRequest(ownerRepo: String, pullRequestNum: Int, accessToken: String): Future[JsValue] = {
    val path = s"repos/$ownerRepo/pulls/$pullRequestNum"
    ws(path, accessToken).get().flatMap(okT[JsValue])
  }

  // todo: paging
  def pullRequests(ownerRepo: String, accessToken: String, filterState: Option[String] = None): Future[JsArray] = {
    val path = s"repos/$ownerRepo/pulls"
    val params = filterState.fold(Map.empty[String, String])(state => Map("state" -> state)).toSeq
    ws(path, accessToken).withQueryStringParameters(params:_*).get().flatMap { response =>
      response.status match {
        // sometimes GitHub says we have access to a repo, but it doesn't actually exist
        case Status.NOT_FOUND => Future.successful(JsArray())
        case _ => okT[JsArray](response)
      }
    }
  }

  def createStatus(ownerRepo: String, sha: String, state: String, url: String, description: String, context: String, accessToken: String): Future[JsObject] = {
    val path = s"repos/$ownerRepo/statuses/$sha"

    val json = Json.obj(
      "state" -> state,
      "target_url" -> url,
      "description" -> description.take(140),
      "context" -> context
    )

    ws(path, accessToken).post(json).flatMap(createdT[JsObject])
  }

  def pullRequestCommits(ownerRepo: String, pullRequestNum: Int, accessToken: String): Future[JsArray] = {
    val path = s"repos/$ownerRepo/pulls/$pullRequestNum/commits"

    ws(path, accessToken).get().flatMap(okT[JsArray])
  }

  def collaborators(ownerRepo: String, accessToken: String): Future[Set[Contributor]] = {
    val path = s"repos/$ownerRepo/collaborators"

    fetchPages(path, accessToken).map { collaborators =>
      collaborators.value.map { json =>
        GitHubUser((json \ "login").as[String])
      }.toSet
    }
  }

  def commentOnIssue(ownerRepo: String, issueNumber: Int, body: String, accessToken: String): Future[JsValue] = {
    // /
    val path = s"repos/$ownerRepo/issues/$issueNumber/comments"
    val json = Json.obj(
      "body" -> body
    )
    ws(path, accessToken).post(json).flatMap(created)
  }

  def commitStatus(ownerRepo: String, ref: String, accessToken: String): Future[JsValue] = {
    val path = s"repos/$ownerRepo/commits/$ref/status"
    ws(path, accessToken).get().flatMap(okT[JsValue])
  }

  def issueComments(ownerRepo: String, issueNumber: Int, accessToken: String): Future[JsArray] = {
    val path = s"repos/$ownerRepo/issues/$issueNumber/comments"
    ws(path, accessToken).get().flatMap(okT[JsArray])
  }

  def updateLabel(ownerRepo: String, name: String, color: String, accessToken: String): Future[JsValue] = {
    val path = s"repos/$ownerRepo/labels/$name"

    val json = Json.obj(
      "name" -> name,
      "color" -> color
    )
    ws(path, accessToken).patch(json).flatMap(okT[JsValue])
  }

  def getIssueLabels(ownerRepo: String, issueNumber: Int, accessToken: String): Future[JsArray] = {
    val path = s"repos/$ownerRepo/issues/$issueNumber/labels"
    ws(path, accessToken).get().flatMap(okT[JsArray])
  }

  def applyLabel(ownerRepo: String, name: String, issueNumber: Int, accessToken: String): Future[JsArray] = {
    val path = s"repos/$ownerRepo/issues/$issueNumber/labels"
    val json =  Json.arr(name)
    ws(path, accessToken).post(json).flatMap(okT[JsArray]).flatMap { jsArray =>
      // validate the label has the correct color
      val maybeColor = jsArray.value.find(_.\("name").as[String] == name).map(_.\("color").as[String])
      val maybeCorrectColor = labels.get(name)

      (maybeColor, maybeCorrectColor) match {
        case (Some(color), Some(correctColor)) if color != correctColor =>
          updateLabel(ownerRepo, name, correctColor, accessToken).flatMap(_ => getIssueLabels(ownerRepo, issueNumber, accessToken))
        case _ =>
          Future.successful(jsArray)
      }
    }
  }

  // github is lying to us here :
  // https://developer.github.com/v3/issues/labels/#remove-a-label-from-an-issue
  // Supposed to return Status: 204 No Content
  // but actually returns 200 : OK
  def removeLabel(ownerRepo: String, name: String, issueNumber: Int, accessToken: String): Future[Unit] = {
    val path = s"repos/$ownerRepo/issues/$issueNumber/labels/$name"
    ws(path, accessToken).delete().flatMap(ok).map(_ => Unit)
  }

  // todo: do not re-apply an existing label
  def toggleLabel(ownerRepo: String, newLabel: String, oldLabel: String, issueNumber: Int, accessToken: String): Future[Option[JsValue]] = {
    getIssueLabels(ownerRepo, issueNumber, accessToken).flatMap { json =>
      val issueLabels = json.value.map(_.\("name").as[String])

      val removeLabelFuture = if (issueLabels.contains(oldLabel)) {
        removeLabel(ownerRepo, oldLabel, issueNumber, accessToken)
      }
      else {
        Future.successful(())
      }

      removeLabelFuture.flatMap { _ =>
        if (!issueLabels.contains(newLabel)) {
          applyLabel(ownerRepo, newLabel, issueNumber, accessToken).map(Some(_))
        }
        else {
          Future.successful(None)
        }
      }
    }
  }

  def orgWebhooks(org: String, accessToken: String): Future[JsArray] = {
    val path = s"orgs/$org/hooks"
    ws(path, accessToken).get().flatMap(okT[JsArray])
  }

  def userOrgMembership(org: String, accessToken: String): Future[JsObject] = {
    val path = s"user/memberships/orgs/$org"
    ws(path, accessToken).get().flatMap(okT[JsObject])
  }

  def addOrgWebhook(org: String, events: Seq[String], url: String, contentType: String, accessToken: String): Future[JsValue] = {
    val path = s"orgs/$org/hooks"
    val json = Json.obj(
      "name" -> "web",
      "events" -> events,
      "config" -> Json.obj(
        "url" -> url,
        "content_type" -> contentType
      )
    )
    ws(path, accessToken).post(json).flatMap(created)
  }

  // todo make the tests cleanup their webhooks, pr webhooks have a limit of 20
  def deleteOrgWebhook(org: String, hookId: Int, accessToken: String): Future[Unit] = {
    val path = s"orgs/$org/hooks/$hookId"
    ws(path, accessToken).delete().flatMap(nocontent).map(_ => Unit)
  }

  def orgMembers(org: String, accessToken: String): Future[JsArray] = {
    val path = s"orgs/$org/members"
    fetchPages(path, accessToken)
  }

  def orgMembersAdd(org: String, username: String, accessToken: String): Future[JsObject] = {
    val path = s"orgs/$org/memberships/$username"
    val json = Json.obj("role" -> "admin")
    ws(path, accessToken).put(json).flatMap(okT[JsObject])
  }

  def activateOrgMembership(org: String, username: String, accessToken: String): Future[JsObject] = {
    val path = s"user/memberships/orgs/$org"
    val json = Json.obj("state" -> "active")
    ws(path, accessToken).patch(json).flatMap(okT[JsObject])
  }

  def commit(ownerRepo: String, message: String, tree: String, parents: Set[String], maybeAuthor: Option[(String, String)], accessToken: String): Future[JsObject] = {
    val path = s"repos/$ownerRepo/git/commits"

    val json = Json.obj(
      "message" -> message,
      "tree" -> tree,
      "parents" -> parents,
      "author" -> maybeAuthor.map { case (name, email) =>
        Json.obj(
          "name" -> name,
          "email" -> email
        )
      }
    )

    ws(path, accessToken).post(json).flatMap(createdT[JsObject])
  }

  def repoCommit(ownerRepo: String, sha: String, accessToken: String): Future[JsObject] = {
    val path = s"repos/$ownerRepo/commits/$sha"
    ws(path, accessToken).get().flatMap(okT[JsObject])
  }

  def updateGitRef(ownerRepo: String, sha: String, ref: String, accessToken: String): Future[JsObject] = {
    val path = s"repos/$ownerRepo/git/refs/$ref"
    val json = Json.obj(
      "sha" -> sha
    )

    ws(path, accessToken).patch(json).flatMap(okT[JsObject])
  }

  def repoCommits(ownerRepo: String, accessToken: String): Future[JsArray] = {
    val path = s"repos/$ownerRepo/commits"
    fetchPages(path, accessToken)
  }

  def pullRequestWithCommitsAndStatus(accessToken:String)(pullRequest: JsValue): Future[JsObject] = {
    val ownerRepo = (pullRequest \ "base" \ "repo" \ "full_name").as[String]
    val prId = (pullRequest \ "number").as[Int]
    val ref = (pullRequest \ "head" \ "sha").as[String]
    val commitsFuture = pullRequestCommits(ownerRepo, prId, accessToken)
    val statusFuture = commitStatus(ownerRepo, ref, accessToken)
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

  def commitAuthor(json: JsValue): String = {
    (json \ "author" \ "login").asOpt[String].getOrElse((json \ "commit" \ "author" \ "email").as[String])
  }

  def integrationInstallations(): Future[JsArray] = {
    val (ws, _) = jwtWs("integration/installations")
    ws.get().flatMap(okT[JsArray])
  }

  def userInstallations(accessToken: String): Future[JsArray] = {
    fetchPagesWithExtractor(s"user/installations", accessToken)(_.\("installations").as[JsArray])
  }

  def installationRepositories(accessToken: String): Future[JsArray] = {
    fetchPagesWithExtractor("installation/repositories", accessToken)(_.\("repositories").as[JsArray])
  }

  def installationRepositories(installationId: Int, accessToken: String): Future[JsArray] = {
    fetchPagesWithExtractor(s"user/installations/$installationId/repositories", accessToken)(_.\("repositories").as[JsArray])
  }

  def createRepo(name: String, maybeOrg: Option[String] = None, autoInit: Boolean = false)(accessToken: String): Future[JsObject] = {
    val path = maybeOrg.fold("user/repos")(org => s"orgs/$org/repos")

    val json = Json.obj(
      "name" -> name,
      "auto_init" -> autoInit
    )

    ws(path, accessToken).post(json).flatMap(createdT[JsObject])
  }

  def repo(ownerRepo: String)(accessToken: String): Future[JsObject] = {
    ws(s"repos/$ownerRepo", accessToken).get().flatMap(okT[JsObject])
  }

  def deleteRepo(ownerRepo: String)(accessToken: String): Future[Unit] = {
    ws(s"repos/$ownerRepo", accessToken).delete().flatMap(nocontent).map(_ => Unit)
  }

  def forkRepo(ownerRepo: String)(accessToken: String): Future[JsObject] = {
    ws(s"repos/$ownerRepo/forks", accessToken).execute(HttpVerbs.POST).flatMap(statusT[JsObject](Status.ACCEPTED, _))
  }

  def getFile(ownerRepo: String, path: String, maybeRef: Option[String] = None)(accessToken: String): Future[JsObject] = {
    val queryString = maybeRef.fold(Map.empty[String, String])(ref => Map("ref" -> ref)).toSeq

    ws(s"repos/$ownerRepo/contents/$path", accessToken).withQueryStringParameters(queryString:_*).get().flatMap(okT[JsObject])
  }

  def editFile(ownerRepo: String, path: String, contents: String, commitMessage: String, sha: String, maybeBranch: Option[String] = None)(accessToken: String): Future[JsObject] = {
    val json = Json.obj(
      "path" -> path,
      "message" -> commitMessage,
      "content" -> Base64.encodeBase64String(contents.getBytes),
      "sha" -> sha
    )

    val jsonWithMaybeBranch = maybeBranch.fold(json) { branch =>
      json + ("branch" -> JsString(branch))
    }

    ws(s"repos/$ownerRepo/contents/$path", accessToken).put(jsonWithMaybeBranch).flatMap(okT[JsObject])
  }

  def createPullRequest(ownerRepo: String, title: String, head: String, base: String, accessToken: String): Future[JsObject] = {
    val json = Json.obj(
      "title" -> title,
      "head" -> head,
      "base" -> base
    )

    ws(s"repos/$ownerRepo/pulls", accessToken).post(json).flatMap(createdT[JsObject])
  }

  def closePullRequest(ownerRepo: String, number: Int, accessToken: String): Future[JsObject] = {
    val json = Json.obj("state" -> "closed")
    ws(s"repos/$ownerRepo/pulls/$number", accessToken).patch(json).flatMap(okT[JsObject])
  }

  def addCollaborator(ownerRepo: String, username: String, accessToken: String): Future[Unit] = {
    ws(s"repos/$ownerRepo/collaborators/$username", accessToken).execute(HttpVerbs.PUT).flatMap(nocontent).map(_ => Unit)
  }

  def createBranch(ownerRepo: String, name: String, sha: String, accessToken: String): Future[JsObject] = {
    val json = Json.obj(
      "ref" -> s"refs/heads/$name",
      "sha" -> sha
    )

    ws(s"repos/$ownerRepo/git/refs", accessToken).post(json).flatMap(createdT[JsObject])
  }

  private def pullRequestHasContributorAndState(contributorId: String, state: String)(pullRequest: JsObject): Boolean = {
    val contributors = (pullRequest \ "commits").as[JsArray].value.map(commitAuthor)
    val prState = (pullRequest \ "status" \ "state").as[String]

    contributors.contains(contributorId) && (state == prState)
  }

  def isOpenPullRequest(pullRequest: JsValue): Boolean = {
    val state = (pullRequest \ "state").as[String]
    state == "open"
  }

  def pullRequestsToValidate(pullRequest: JsValue, accessToken: String): Future[Map[JsObject, String]] = {
    if (isOpenPullRequest(pullRequest)) {
      pullRequestWithCommitsAndStatus(accessToken)(pullRequest).map { pullRequestDetails =>
        Map(pullRequestDetails -> accessToken)
      }
    }
    else {
      Future.successful(Map.empty[JsObject, String])
    }
  }

  // todo: optimize this
  private def pullRequestsNeedingValidationForAccessToken(signerGitHubId: String, repos: Seq[JsObject], accessToken: String): Future[Map[JsObject, String]] = {
    val repoNames = repos.map(_.\("full_name").as[String]).toSet

    for {
      allPullRequests <- Future.sequence(repoNames.map(ownerRepo => pullRequests(ownerRepo, accessToken, Some("open"))))
      pullRequestWithCommitsAndStatus <- Future.sequence(allPullRequests.flatMap(_.value).map(pullRequestWithCommitsAndStatus(accessToken)))
    } yield pullRequestWithCommitsAndStatus.filter(pullRequestHasContributorAndState(signerGitHubId, "failure")).map { pullRequest =>
      pullRequest -> accessToken
    }.toMap
  }

  def pullRequestsToBeValidated(signerGitHubId: String): Future[Map[JsObject, String]] = {
    def integrationTokens(integrationInstallationIds: Seq[Int]): Future[Seq[String]] = {
      Future.sequence {
        integrationInstallationIds.map { integrationInstallationId =>
          installationAccessTokens(integrationInstallationId).map { json =>
            (json \ "token").as[String]
          }
        }
      }
    }

    def integrationAccessTokenToPullRequests(accessToken: String): Future[Map[JsObject, String]] = {
      installationRepositories(accessToken).flatMap { repos =>
        pullRequestsNeedingValidationForAccessToken(signerGitHubId, repos.value.map(_.as[JsObject]), accessToken)
      }
    }

    for {
      integrations <- integrationInstallations()
      integrationInstallationIds = integrations.value.map(_.\("id").as[Int])
      integrationAccessTokens <- integrationTokens(integrationInstallationIds)
      pullRequests <- Future.sequence(integrationAccessTokens.map(integrationAccessTokenToPullRequests))
    } yield pullRequests.flatten.toMap
  }

  def pullRequestUserCommitters(ownerRepo: String, prNumber: Int, sha: String, accessToken: String): Future[Set[Contributor]] = {
    val prCommitsFuture = pullRequestCommits(ownerRepo, prNumber, accessToken)

    prCommitsFuture.map { commits =>
      val onlyUserCommits = commits.value.filterNot { commit =>
        val maybeAuthorType = (commit \ "author" \ "type").asOpt[String]
        maybeAuthorType.contains("Bot")
      }

      val commitsWithMaybeLogins = onlyUserCommits.map { commit =>
        val maybeAuthorLogin = (commit \ "author" \ "login").asOpt[String]

        maybeAuthorLogin.fold[Contributor] {
          val author = (commit \ "commit" \ "author").as[JsObject]
          val maybeName = (author \ "name").asOpt[String]
          val maybeEmail = (author \ "email").asOpt[String]
          UnknownCommitter(maybeName, maybeEmail)
        } (GitHubUser)
      }

      commitsWithMaybeLogins.toSet
    }
  }

  def repoContributors(ownerRepo: String, accessToken: String): Future[Set[ContributorWithMetrics]] = {
    for {
      commits <- repoCommits(ownerRepo, accessToken)
    } yield {
      val contributorsWithCommits = commits.value.groupBy[Contributor] { commit =>
        (commit \ "author").asOpt[GitHubUser].getOrElse {
          (commit \ "commit" \ "author").as[UnknownCommitter]
        }
      }

      contributorsWithCommits.map { case (contributor, contributorCommits) =>
        ContributorWithMetrics(contributor, contributorCommits.size)
      }.toSet
    }
  }

  def externalContributors(contributors: Set[Contributor], internalContributors: Set[Contributor]): Set[Contributor] = {
    contributors.diff(internalContributors)
  }

  def externalContributorsForPullRequest(ownerRepo: String, prNumber: Int, sha: String, accessToken: String): Future[Set[Contributor]] = {
    val pullRequestCommittersFuture = pullRequestUserCommitters(ownerRepo, prNumber, sha, accessToken)
    val collaboratorsFuture = collaborators(ownerRepo, accessToken)

    for {
      pullRequestCommitters <- pullRequestCommittersFuture
      collaborators <- collaboratorsFuture
    } yield externalContributors(pullRequestCommitters, collaborators)
  }

  def committersWithoutClas(externalContributors: Set[Contributor])(clasForCommitters: Set[Contributor] => Future[Set[ClaSignature]]): Future[Set[Contributor]] = {
    for {
      clasForCommitters <- clasForCommitters(externalContributors)
    } yield {
      // todo: maybe check latest CLA version
      // todo: ability to exclude users with a given email address domain
      externalContributors.diff(clasForCommitters.map(claSignature => GitHubUser(claSignature.contactGitHubId)))
    }
  }

  def missingClaComment(ownerRepo: String, prNumber: Int, sha: String, claUrl: String, gitHubUsers: Set[GitHubUser], accessToken: String): Future[Option[JsValue]] = {
    if (gitHubUsers.nonEmpty) {
      issueComments(ownerRepo, prNumber, accessToken).flatMap { comments =>
        val message = messagesApi("cla.missing", gitHubUsers.map(_.username).mkString("@", " @", ""), orgName,  claUrl)

        val alreadyCommented = comments.value.exists(_.\("body").as[String] == message)
        if (!alreadyCommented) {
          commentOnIssue(ownerRepo, prNumber, message, accessToken).map(Some(_))
        }
        else {
          Future.successful(None)
        }
      }
    }
    else {
      Future.successful(None)
    }
  }

  def authorLoginNotFoundComment(ownerRepo: String, prNumber: Int, claUrl: String, unknownCommitters: Set[UnknownCommitter], accessToken: String): Future[Option[JsValue]] = {
    if (unknownCommitters.nonEmpty) {
      val committers = unknownCommitters.flatMap(_.toStringOpt())

      issueComments(ownerRepo, prNumber, accessToken).flatMap { comments =>

        val message = if (committers.isEmpty) {
          messagesApi("cla.author-not-found-without-name", orgName, claUrl)
        }
        else {
          val names = committers.mkString(" ")
          messagesApi("cla.author-not-found-with-name", names, orgName, claUrl)
        }

        val alreadyCommented = comments.value.exists(_.\("body").as[String] == message)
        if (!alreadyCommented) {
          commentOnIssue(ownerRepo, prNumber, message, accessToken).map(Some(_))
        }
        else {
          Future.successful(None)
        }
      }
    }
    else {
      Future.successful(None)
    }
  }

  def updatePullRequestLabel(ownerRepo: String, prNumber: Int, hasExternalContributors: Boolean, hasMissingClas: Boolean, accessToken: String): Future[Option[JsValue]] = {
    if (hasExternalContributors) {
      if (hasMissingClas) {
        toggleLabel(ownerRepo, "cla:missing", "cla:signed", prNumber, accessToken)
      }
      else {
        toggleLabel(ownerRepo, "cla:signed", "cla:missing", prNumber, accessToken)
      }
    }
    else {
      Future.successful(None)
    }
  }

  def validatePullRequest(pullRequest: JsObject, token: String, claUrl: String, statusUrlF: (String, String, Int) => String)(clasForCommitters: (Set[Contributor]) => Future[Set[ClaSignature]]): Future[(Set[Contributor], Set[Contributor], JsObject)] = {
    val owner = (pullRequest \ "pull_request" \ "base" \ "repo" \ "owner" \ "login").as[String]
    val repo = (pullRequest \ "pull_request" \ "base" \ "repo" \ "name").as[String]
    val ownerRepo = (pullRequest \ "pull_request" \ "base" \ "repo" \ "full_name").as[String]
    val prNumber = (pullRequest \ "pull_request" \ "number").as[Int]
    val sha = (pullRequest \ "pull_request" \ "head" \ "sha").as[String]

    val statusUrl = statusUrlF(owner, repo, prNumber)

    def addComment(gitHubUsers: Set[GitHubUser], unknownCommitters: Set[UnknownCommitter]): Future[(Option[JsValue], Option[JsValue])] = {
      val gitHubUsersCommentFuture = missingClaComment(ownerRepo, prNumber, sha, claUrl, gitHubUsers, token)
      val unknownCommittersCommentFuture = authorLoginNotFoundComment(ownerRepo, prNumber, claUrl, unknownCommitters, token)

      for {
        gitHubUsersComment <- gitHubUsersCommentFuture
        unknownCommittersComment <- unknownCommittersCommentFuture
      } yield gitHubUsersComment -> unknownCommittersComment
    }

    def updateStatus(gitHubUsers: Set[GitHubUser], unknownCommitters: Set[UnknownCommitter]): Future[JsObject] = {

      val (state, description) = (gitHubUsers.isEmpty, unknownCommitters.isEmpty) match {
        case (true, true) =>
          ("success", "All contributors have signed the CLA")
        case (false, true) =>
          ("failure", "One or more contributors need to sign the CLA")
        case _ =>
          ("error", "Commit authors must be associated with GitHub users")
      }

      createStatus(ownerRepo, sha, state, statusUrl, description, gitHubBotName, token)
    }

    for {
      _ <- createStatus(ownerRepo, sha, "pending", statusUrl, "The CLA verifier is running", gitHubBotName, token)
      externalContributors <- externalContributorsForPullRequest(ownerRepo, prNumber, sha, token)
      committersWithoutClas <- committersWithoutClas(externalContributors)(clasForCommitters)
      gitHubUsers = committersWithoutClas.collect { case gitHubUser: GitHubUser => gitHubUser }
      unknownCommitters = committersWithoutClas.collect { case unknownCommitter: UnknownCommitter => unknownCommitter }
      _ <- updatePullRequestLabel(ownerRepo, prNumber, externalContributors.nonEmpty, committersWithoutClas.nonEmpty, token)
      _ <- addComment(gitHubUsers, unknownCommitters)
      status <- updateStatus(gitHubUsers, unknownCommitters)
    } yield (externalContributors, committersWithoutClas, status)
  }

  def validatePullRequests(pullRequests: Map[JsObject, String], claUrl: String, statusUrlF: (String, String, Int) => String)(clasForCommitters: (Set[Contributor]) => Future[Set[ClaSignature]]): Future[Iterable[(Set[Contributor], Set[Contributor], JsObject)]] = {
    Future.sequence {
      pullRequests.map { case (pullRequest, token) =>
        validatePullRequest(pullRequest, token, claUrl, statusUrlF)(clasForCommitters)
      }
    }
  }

  def orgsWithRole(roles: Seq[String])(jsArray: JsArray): Seq[GitHub.Org] = {
    jsArray.value.filter(org => roles.contains(org.\("role").as[String])).map(_.as[GitHub.Org])
  }

  def isOrgAdmin(org: String, accessToken: String): Future[Boolean] = {
    userMembershipOrgs(Some("active"), accessToken).map { jsArray =>
      val orgs = orgsWithRole(Seq("admin"))(jsArray)
      orgs.exists(_.login == org)
    }
  }

  def integrationAndUserOrgs(userAccessToken: String): Future[Map[String, String]] = {
    def orgIntegrationInstallationForUser(userOrgs: Seq[GitHub.Org])(integrationInstallation: JsValue): Boolean = {
      val isOrg = (integrationInstallation \ "account" \ "type").as[String] == "Organization"
      val userHasAccess = userOrgs.exists(_.login == (integrationInstallation \ "account" \ "login").as[String])
      isOrg && userHasAccess
    }

    def orgWithAccessToken(integrationInstallation: JsValue): Future[(String, String)] = {
      val org = (integrationInstallation \ "account" \ "login").as[String]
      val integrationInstallationId = (integrationInstallation \ "id").as[Int]
      installationAccessTokens(integrationInstallationId).map { json =>
        (org, (json \ "token").as[String])
      }
    }

    for {
      userOrgs <- userMembershipOrgs(Some("active"), userAccessToken).map(orgsWithRole(Seq("admin")))
      integrationInstallations <- integrationInstallations()
      integrationInstallationsForUser = integrationInstallations.value.filter(orgIntegrationInstallationForUser(userOrgs))
      integrationAccessTokens <- Future.sequence(integrationInstallationsForUser.map(orgWithAccessToken))
    } yield integrationAccessTokens.toMap
  }

  private def ok(response: WSResponse): Future[WSResponse] = status(Status.OK, response)

  private def okT[T](response: WSResponse)(implicit r: Reads[T]): Future[T] = statusT[T](Status.OK, response)

  private def created(response: WSResponse): Future[JsValue] = statusT[JsValue](Status.CREATED, response)

  private def createdT[T](response: WSResponse)(implicit r: Reads[T]): Future[T] = statusT[T](Status.CREATED, response)

  private def nocontent(response: WSResponse): Future[WSResponse] = status(Status.NO_CONTENT, response)

  private def statusT[T](statusCode: Int, response: WSResponse)(implicit r: Reads[T]): Future[T] = {
    if (response.status == statusCode) {
      response.json.asOpt[T].fold {
        Future.failed[T](GitHub.InvalidResponseBody(response.body))
      } (Future.successful)
    } else {
      val messageTry = Try((response.json \ "message").as[String])
      val ahcResponse = response.underlying[play.shaded.ahc.org.asynchttpclient.Response]
      Future.failed(GitHub.IncorrectResponseStatus(statusCode, response.status, ahcResponse.getUri.toJavaNetURI, messageTry.getOrElse(response.body)))
    }
  }

  private def status(statusCode: Int, response: WSResponse): Future[WSResponse] = {
    if (response.status == statusCode) {
      Future.successful(response)
    } else {
      val messageTry = Try((response.json \ "message").as[String])
      val ahcResponse = response.underlying[play.shaded.ahc.org.asynchttpclient.Response]
      Future.failed(GitHub.IncorrectResponseStatus(statusCode, response.status, ahcResponse.getUri.toJavaNetURI, messageTry.getOrElse(response.body)))
    }
  }

}

object GitHub {

  case class Repo(ownerRepo: String)

  object Repo {
    implicit val jsonReads: Reads[Repo] = (__ \ "full_name").read[String].map(Repo(_))
  }

  case class Org(login: String)

  object Org {
    implicit val jsonReads: Reads[Org] = (__ \ "organization" \ "login").read[String].map(Org(_))
    implicit val jsonWrites: Writes[Org] = Json.writes[Org]
  }

  case class IncorrectResponseStatus(expectedStatusCode: Int, actualStatusCode: Int, uri: URI, message: String) extends Exception {
    override def getMessage: String = s"$uri - Expected status code $expectedStatusCode but got $actualStatusCode - $message"
  }

  case class InvalidResponseBody(body: String) extends Exception {
    override def getMessage: String = "Response body was not in the expected form"
  }

  sealed trait Contributor
  case class GitHubUser(username: String) extends Contributor
  case class UnknownCommitter(maybeName: Option[String], maybeEmail: Option[String]) extends Contributor {

    def publicEmail(email: String): Option[String] = {
      def obfuscate(s: String): String = {
        s.take(1) + "***"
      }

      email.split("@") match {
        case Array(username, domain) =>
          val domainParts = domain.split("\\.").reverse
          val nonRootDomainParts = domainParts.tail.map(obfuscate).reverse.mkString(".")
          val rootDomainPart = domainParts.head
          Some(obfuscate(username) + "@" + nonRootDomainParts + "." + rootDomainPart)
        case _ =>
          None
      }
    }

    def toStringOpt(hideEmail: Boolean = true): Option[String] = {
      val maybeEmailPublic = if (hideEmail) {
        maybeEmail.flatMap(publicEmail)
      }
      else {
        maybeEmail
      }

      (maybeName, maybeEmailPublic) match {
        case (Some(name), Some(email)) =>
          Some(s"$name <$email>")
        case (Some(name), None) =>
          Some(name)
        case (None, Some(email)) =>
          Some(email)
        case (None, None) =>
          None
      }
    }
  }

  case class ContributorWithMetrics(contributor: Contributor, numCommits: Int)

  implicit val gitHubUserReads: Reads[GitHubUser] = (__ \ "login").read[String].map(GitHubUser)
  implicit val unknownCommitterReads: Reads[UnknownCommitter] = (
    (__ \ "name").readNullable[String] ~
    (__ \ "email").readNullable[String]
  )(UnknownCommitter)

}
