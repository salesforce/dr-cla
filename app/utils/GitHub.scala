/*
 * Copyright (c) 2017, salesforce.com, inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package utils

import java.io.StringReader
import java.net.URL
import java.security.KeyPair
import javax.inject.Inject

import models.ClaSignature
import org.apache.commons.codec.binary.Base64
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.{PEMKeyPair, PEMParser}
import pdi.jwt.{JwtAlgorithm, JwtClaim, JwtJson}
import play.api.Configuration
import play.api.http.{HeaderNames, MimeTypes, Status}
import play.api.libs.json.Reads._
import play.api.libs.json._
import play.api.libs.ws.{WSClient, WSRequest, WSResponse}
import play.api.mvc.Results.EmptyContent

import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions
import scala.util.Try

class GitHub @Inject() (configuration: Configuration, ws: WSClient) (implicit ec: ExecutionContext) {

  val clientId = configuration.getString("github.oauth.client-id").get
  val clientSecret = configuration.getString("github.oauth.client-secret").get
  val integrationToken = configuration.getString("github.token").get
  lazy val integrationLoginFuture = userInfo(integrationToken).map(_.\("login").as[String])

  val integrationId = configuration.getString("github.integration.id").get
  val integrationSlug = configuration.getString("github.integration.slug").get

  val integrationKeyPair: KeyPair = {
    val privateKeyString = configuration.getString("github.integration.private-key").get

    val stringReader = new StringReader(privateKeyString)

    val pemParser = new PEMParser(stringReader)

    val pemObject = pemParser.readObject()

    new JcaPEMKeyConverter().getKeyPair(pemObject.asInstanceOf[PEMKeyPair])
  }

  val maybeIntegrationSecretToken = configuration.getString("github.integration.secret-token")

  val labels: Map[String, String] = Map(("cla:missing", "c40d0d"), ("cla:signed", "5ebc41"))

  def ws(path: String, accessToken: String): WSRequest = {
    ws
      .url(s"https://api.github.com/$path")
      .withHeaders(
        HeaderNames.AUTHORIZATION -> s"token $accessToken",
        HeaderNames.ACCEPT -> "application/vnd.github.machine-man-preview+json"
      )
  }

  def accessToken(code: String): Future[String] = {
    val wsFuture = ws.url("https://github.com/login/oauth/access_token").withQueryString(
      "client_id" -> clientId,
      "client_secret" -> clientSecret,
      "code" -> code
    ).withHeaders(HeaderNames.ACCEPT -> MimeTypes.JSON).post(EmptyContent())

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
      .withHeaders(
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
    import io.netty.handler.codec.http.QueryStringDecoder
    import collection.JavaConverters._

    implicit class Regex(sc: StringContext) {
      def r = new scala.util.matching.Regex(sc.parts.mkString, sc.parts.tail.map(_ => "x"): _*)
    }

    def req(path: String, accessToken: String, page: Int, pageSize: Int): Future[WSResponse] = {
      ws(path, accessToken).withQueryString("page" -> page.toString, "per_page" -> pageSize.toString).get()
    }

    // get the first page
    req(path, accessToken, 1, pageSize).flatMap { response =>
      val firstPageRepos = response.json.as[JsArray]

      def urlToPage(urlString: String): Int = {
        val url = new URL(urlString)
        val params = new QueryStringDecoder(url.toURI.getRawQuery, false).parameters.asScala.mapValues(_.asScala.toSeq).toMap
        params("page").head.toInt
      }

      val pages = response.header("Link") match {
        case Some(r"""<(.*)$n>; rel="next", <(.*)$l>; rel="last"""") =>
          Range(urlToPage(n), urlToPage(l) + 1)
        case _ =>
          Range(0, 0)
      }

      val pagesFutures = pages.map(req(path, accessToken, _, pageSize).map(_.json.as[JsArray]))

      // assume numeric paging so we can parallelize
      Future.fold(pagesFutures)(firstPageRepos)(_ ++ _)
    }
  }

  // deals with pagination
  private def userOrOrgRepos(userOrOrg: Either[String, String], accessToken: String, pageSize: Int): Future[JsArray] = {

    val path = userOrOrg match {
      case Left(user) => s"user/repos"
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
    ws("user/memberships/orgs", accessToken).withQueryString(maybeParams:_*).get().flatMap(okT[JsArray])
  }

  def userInfo(accessToken: String): Future[JsValue] = {
    ws("user", accessToken).get().flatMap { response =>
      response.status match {
        case Status.OK => Future.successful(response.json)
        case _ => Future.failed(new Exception(response.body))
      }
    }
  }

  def getPullRequest(ownerRepo: String, pullRequestId: Int, accessToken: String): Future[JsValue] = {
    val path = s"repos/$ownerRepo/pulls/$pullRequestId"
    ws(path, accessToken).get().flatMap(okT[JsValue])
  }

  def pullRequests(ownerRepo: String, accessToken: String): Future[JsArray] = {
    val path = s"repos/$ownerRepo/pulls"
    ws(path, accessToken).get().flatMap { response =>
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

  def pullRequestCommits(ownerRepo: String, pullRequestId: Int, accessToken: String): Future[JsArray] = {
    val path = s"repos/$ownerRepo/pulls/$pullRequestId/commits"

    ws(path, accessToken).get().flatMap(okT[JsArray])
  }

  def collaborators(ownerRepo: String, accessToken: String): Future[JsArray] = {
    val path = s"repos/$ownerRepo/collaborators"

    fetchPages(path, accessToken)
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
    ws(path, accessToken).delete().flatMap(ok)
  }

  def toggleLabel(ownerRepo: String, newLabel: String, oldLabel: String, issueNumber: Int, accessToken: String): Future[JsValue] = {
    val applyLabelFuture = applyLabel(ownerRepo, newLabel, issueNumber, accessToken)

    removeLabel(ownerRepo, oldLabel, issueNumber, accessToken).flatMap(_ => applyLabelFuture).recoverWith {
      case _ => applyLabelFuture
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
    ws(path, accessToken).delete().flatMap(nocontent)
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

  // todo: definitely will need paging
  def repoCommits(ownerRepo: String, accessToken: String): Future[JsArray] = {
    val path = s"repos/$ownerRepo/commits"
    ws(path, accessToken).get().flatMap(okT[JsArray])
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

  def installationRepositories(accessToken: String): Future[JsArray] = {
    ws("installation/repositories", accessToken).get().flatMap(okT[JsObject]).flatMap { json =>
      (json \ "repositories").asOpt[JsArray].fold(Future.failed[JsArray](new IllegalStateException("Data was not in the expected form")))(Future.successful)
    }
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
    ws(s"repos/$ownerRepo", accessToken).delete().flatMap(nocontent)
  }

  def forkRepo(ownerRepo: String)(accessToken: String): Future[JsObject] = {
    ws(s"repos/$ownerRepo/forks", accessToken).post(EmptyContent()).flatMap(statusT[JsObject](Status.ACCEPTED, _))
  }

  def getFile(ownerRepo: String, path: String, maybeRef: Option[String] = None)(accessToken: String): Future[JsObject] = {
    val queryString = maybeRef.fold(Map.empty[String, String])(ref => Map("ref" -> ref)).toSeq

    ws(s"repos/$ownerRepo/contents/$path", accessToken).withQueryString(queryString:_*).get().flatMap(okT[JsObject])
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

  def addCollaborator(ownerRepo: String, username: String, accessToken: String): Future[Unit] = {
    ws(s"repos/$ownerRepo/collaborators/$username", accessToken).put(EmptyContent()).flatMap(nocontent)
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

  // todo: optimize this
  private def pullRequestsNeedingValidationForAccessToken(signerGitHubId: String, repos: Seq[JsObject], accessToken: String): Future[Map[JsObject, String]] = {
    val repoNames = repos.map(_.\("full_name").as[String]).toSet

    for {
      allPullRequests <- Future.sequence(repoNames.map(ownerRepo => pullRequests(ownerRepo, accessToken)))
      pullRequestWithCommitsAndStatus <- Future.sequence(allPullRequests.flatMap(_.value).map(pullRequestWithCommitsAndStatus(accessToken)))
    } yield pullRequestWithCommitsAndStatus.filter(pullRequestHasContributorAndState(signerGitHubId, "failure")).map { pullRequest =>
      pullRequest -> accessToken
    }.toMap
  }

  def pullRequestsToBeValidatedViaDirectAccess(signerGitHubId: String, accessToken: String): Future[Map[JsObject, String]] = {
    for {
      repos <- allRepos(accessToken).map(_.value.map(_.as[JsObject]))
      pullRequests <- pullRequestsNeedingValidationForAccessToken(signerGitHubId, repos, accessToken)
    } yield pullRequests
  }

  def pullRequestsToBeValidatedViaIntegrations(signerGitHubId: String): Future[Map[JsObject, String]] = {

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

  def pullRequestsToBeValidated(signerGitHubId: String, accessToken: String): Future[Map[JsObject, String]] = {
    for {
      pullRequestsViaDirectAccess <- pullRequestsToBeValidatedViaDirectAccess(signerGitHubId, accessToken)
      pullRequestsViaIntegrations <- pullRequestsToBeValidatedViaIntegrations(signerGitHubId)
    } yield pullRequestsViaDirectAccess ++ pullRequestsViaIntegrations
  }

  def pullRequestCommitters(ownerRepo: String, prNumber: Int, sha: String, accessToken: String): Future[Set[String]] = {
    val prCommitsFuture = pullRequestCommits(ownerRepo, prNumber, accessToken)

    prCommitsFuture.flatMap { commits =>
      val commitsWithMaybeLogins = commits.value.map { commit =>
        val maybeAuthorLogin = (commit \ "author" \ "login").asOpt[String]
        maybeAuthorLogin.fold(Future.failed[String](GitHub.AuthorLoginNotFound(sha, (commit \ "commit" \ "author").as[JsObject])))(Future.successful)
      }

      Future.sequence(commitsWithMaybeLogins).map(_.toSet)
    }
  }

  def repoContributors(ownerRepo: String, accessToken: String): Future[JsArray] = {
    val path = s"repos/$ownerRepo/contributors"

    ws(path, accessToken).get().flatMap(okT[JsArray])
  }

  def externalContributors(contributors: Set[String], internalContributors: Set[String]): Set[String] = {
    contributors.diff(internalContributors)
  }

  def logins(users: JsArray): Set[String] = {
    users.value.map(_.\("login").as[String]).toSet
  }

  def contributorLoginsAndContributions(contributors: JsArray): Map[String, Int] = {
    val loginAndCount = contributors.value.map { contributor =>
      val login = contributor.\("login").as[String]
      val count = contributor.\("contributions").as[Int]
      (login, count)
    }

    loginAndCount.toMap
  }

  def externalContributorsForPullRequest(ownerRepo: String, prNumber: Int, sha: String, accessToken: String): Future[Set[String]] = {
    val pullRequestCommittersFuture = pullRequestCommitters(ownerRepo, prNumber, sha, accessToken)
    val collaboratorsFuture = collaborators(ownerRepo, accessToken)

    for {
      pullRequestCommitters <- pullRequestCommittersFuture
      collaborators <- collaboratorsFuture
    } yield externalContributors(pullRequestCommitters, logins(collaborators))
  }

  def committersWithoutClas(externalContributors: Set[String])(clasForCommitters: (Set[String]) => Future[Set[ClaSignature]]): Future[Set[String]] = {
    for {
      clasForCommitters <- clasForCommitters(externalContributors)
    } yield {
      // todo: maybe check latest CLA version
      externalContributors.diff(clasForCommitters.map(_.contact.gitHubId))
    }
  }

  def missingClaComment(ownerRepo: String, prNumber: Int, sha: String, claUrl: String, committersWithoutClas: Set[String], accessToken: String): Future[Either[JsValue, Unit]] = {
    if (committersWithoutClas.nonEmpty) {
      integrationLoginFuture.flatMap { integrationLogin =>
        issueComments(ownerRepo, prNumber, accessToken).flatMap { comments =>
          val alreadyCommented = comments.value.exists(_.\("user").\("login").as[String].startsWith(integrationLogin))
          if (!alreadyCommented) {
            val body = s"Thanks for the contribution!  Before we can merge this, we need ${committersWithoutClas.map(" @" + _).mkString} to [sign the Salesforce Contributor License Agreement]($claUrl)."
            commentOnIssue(ownerRepo, prNumber, body, accessToken).map(Left(_))
          }
          else {
            Future.successful(Right(Unit))
          }
        }
      }
    }
    else {
      Future.successful(Right(Unit))
    }
  }

  def updatePullRequestLabel(ownerRepo: String, prNumber: Int, hasExternalContributors: Boolean, hasMissingClas: Boolean, accessToken: String): Future[JsValue] = {
    if (hasExternalContributors) {
      if (hasMissingClas) {
        toggleLabel(ownerRepo, "cla:missing", "cla:signed", prNumber, accessToken)
      }
      else {
        toggleLabel(ownerRepo, "cla:signed", "cla:missing", prNumber, accessToken)
      }
    }
    else {
      Future.successful(Json.obj())
    }
  }

  def validatePullRequests(pullRequests: Map[JsObject, String], claUrl: String)(clasForCommitters: (Set[String]) => Future[Set[ClaSignature]]): Future[Iterable[JsObject]] = {
    Future.sequence {
      pullRequests.map { case (pullRequest, token) =>
        val ownerRepo = (pullRequest \ "pull_request" \ "base" \ "repo" \ "full_name").as[String]
        val prNumber = (pullRequest \ "pull_request" \ "number").as[Int]
        val sha = (pullRequest \ "pull_request" \ "head" \ "sha").as[String]

        val pullRequestStatusFuture = for {
          _ <- createStatus(ownerRepo, sha, "pending", claUrl, "The CLA verifier is running", "salesforce-cla", token)
          externalContributors <- externalContributorsForPullRequest(ownerRepo, prNumber, sha, token)
          committersWithoutClas <- committersWithoutClas(externalContributors)(clasForCommitters)
          _ <- missingClaComment(ownerRepo, prNumber, sha, claUrl, committersWithoutClas, token)
          _ <- updatePullRequestLabel(ownerRepo, prNumber, externalContributors.nonEmpty, committersWithoutClas.nonEmpty, token)
          (state, description) = if (committersWithoutClas.isEmpty) ("success", "All contributors have signed the CLA") else ("failure", "One or more contributors need to sign the CLA")
          pullRequestStatus <- createStatus(ownerRepo, sha, state, claUrl, description, "salesforce-cla", token)
        } yield pullRequestStatus

        pullRequestStatusFuture.recoverWith {
          case e: GitHub.AuthorLoginNotFound =>
            createStatus(ownerRepo, sha, "error", claUrl, e.getMessage, "salesforce-cla", token)
        }
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

  private def ok(response: WSResponse): Future[Unit] = status(Status.OK, response)

  private def okT[T](response: WSResponse)(implicit r: Reads[T]): Future[T] = statusT[T](Status.OK, response)

  private def created(response: WSResponse): Future[JsValue] = statusT[JsValue](Status.CREATED, response)

  private def createdT[T](response: WSResponse)(implicit r: Reads[T]): Future[T] = statusT[T](Status.CREATED, response)

  private def nocontent(response: WSResponse): Future[Unit] = status(Status.NO_CONTENT, response)

  private def statusT[T](statusCode: Int, response: WSResponse)(implicit r: Reads[T]): Future[T] = {
    if (response.status == statusCode) {
      response.json.asOpt[T].fold {
        Future.failed[T](GitHub.InvalidResponseBody(response.body))
      } (Future.successful)
    } else {
      val messageTry = Try((response.json \ "message").as[String])
      Future.failed(GitHub.IncorrectResponseStatus(statusCode, response.status, messageTry.getOrElse(response.body)))
    }
  }

  private def status(statusCode: Int, response: WSResponse): Future[Unit] = {
    if (response.status == statusCode) {
      Future.successful(Unit)
    } else {
      val messageTry = Try((response.json \ "message").as[String])
      Future.failed(GitHub.IncorrectResponseStatus(statusCode, response.status, messageTry.getOrElse(response.body)))
    }
  }

  private def seqFutures[T, U](items: TraversableOnce[T])(f: T => Future[U]): Future[List[U]] = {
    items.foldLeft(Future.successful[List[U]](Nil)) {
      (futures, item) => futures.flatMap { values =>
        f(item).map(_ :: values)
      }
    } map (_.reverse)
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

  case class AuthorLoginNotFound(sha: String, author: JsObject) extends Exception {
    override def getMessage: String = {
      val maybeName = (author \ "name").asOpt[String]
      maybeName.fold(s"Could not find a GitHub user on commit $sha") { name =>
        s"Could not find a GitHub user for $name on commit $sha"
      }
    }
  }

  case class IncorrectResponseStatus(expectedStatusCode: Int, actualStatusCode: Int, message: String) extends Exception {
    override def getMessage: String = s"Expected status code $expectedStatusCode but got $actualStatusCode - $message"
  }

  case class InvalidResponseBody(body: String) extends Exception {
    override def getMessage: String = "Response body was not in the expected form"
  }

}
