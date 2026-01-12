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
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._
import play.api.libs.ws.{WSClient, WSRequest, WSResponse}
import play.api.mvc.{PathBindable, QueryStringBindable}

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

  val maybeDomain = configuration.getOptional[String]("app.organization.domain")
  val maybeInstructionsUrl = configuration.getOptional[String]("app.organization.internal-instructions-url")

  val integrationKeyPair: KeyPair = {
    val privateKeyString = configuration.get[String]("github.integration.private-key")

    val stringReader = new StringReader(privateKeyString)

    val pemParser = new PEMParser(stringReader)

    val pemObject = pemParser.readObject()

    new JcaPEMKeyConverter().getKeyPair(pemObject.asInstanceOf[PEMKeyPair])
  }

  val maybeIntegrationSecretToken = configuration.getOptional[String]("github.integration.secret-token")

  def ws(path: String, accessToken: String): WSRequest = {
    ws
      .url(s"https://api.github.com/$path")
      .withHttpHeaders(
        HeaderNames.AUTHORIZATION -> s"token $accessToken",
        HeaderNames.ACCEPT -> "application/vnd.github.machine-man-preview+json"
      )
  }

  def accessToken(code: String, oauthClientId: String, oauthClientSecret: String): Future[String] = {
    configuration.getOptional[String]("github.token") match {
      case Some(token) => Future.successful(token)
      case None =>
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
    val (ws, claim) = jwtWs(s"app/installations/$installationId/access_tokens")

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

  def userRepos(user: User, accessToken: String, pageSize: Int = 100): Future[Set[OwnerRepo]] = {
    val path = s"user/repos"
    fetchPages(path, accessToken).map(_.as[Set[OwnerRepo]])
  }

  def userInfo(accessToken: String): Future[User] = {
    ws("user", accessToken).get().flatMap { response =>
      response.status match {
        case Status.OK => Future.successful(response.json.as[User])
        case _ => Future.failed(new Exception(response.body))
      }
    }
  }

  def getPullRequest(ownerRepo: OwnerRepo, pullRequestNum: Int, accessToken: String): Future[JsValue] = {
    val path = s"repos/$ownerRepo/pulls/$pullRequestNum"
    ws(path, accessToken).get().flatMap(okT[JsValue])
  }

  // todo: paging
  def pullRequests(ownerRepo: OwnerRepo, accessToken: String, filterState: Option[String] = None): Future[JsArray] = {
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

  def createStatus(ownerRepo: OwnerRepo, sha: String, state: String, url: String, description: String, context: String, accessToken: String): Future[JsObject] = {
    val path = s"repos/$ownerRepo/statuses/$sha"

    val json = Json.obj(
      "state" -> state,
      "target_url" -> url,
      "description" -> description.take(140),
      "context" -> context
    )

    ws(path, accessToken).post(json).flatMap(createdT[JsObject])
  }

  def pullRequestCommits(ownerRepo: OwnerRepo, pullRequestNum: Int, accessToken: String): Future[JsArray] = {
    val path = s"repos/$ownerRepo/pulls/$pullRequestNum/commits"

    ws(path, accessToken).get().flatMap(okT[JsArray])
  }

  def commentOnIssue(ownerRepo: OwnerRepo, issueNumber: Int, body: String, accessToken: String): Future[JsValue] = {
    // /
    val path = s"repos/$ownerRepo/issues/$issueNumber/comments"
    val json = Json.obj(
      "body" -> body
    )
    ws(path, accessToken).post(json).flatMap(created)
  }

  def commitStatus(ownerRepo: OwnerRepo, ref: String, accessToken: String): Future[JsValue] = {
    val path = s"repos/$ownerRepo/commits/$ref/status"
    ws(path, accessToken).get().flatMap(okT[JsValue])
  }

  def issueComments(ownerRepo: OwnerRepo, issueNumber: Int, accessToken: String): Future[JsArray] = {
    val path = s"repos/$ownerRepo/issues/$issueNumber/comments"
    ws(path, accessToken).get().flatMap(okT[JsArray])
  }

  def updateLabel(ownerRepo: OwnerRepo, label: Label, accessToken: String): Future[JsValue] = {
    val path = s"repos/$ownerRepo/labels/${label.name}"

    val json = Json.obj(
      "name" -> label.name,
      "color" -> label.color
    )
    ws(path, accessToken).patch(json).flatMap(okT[JsValue])
  }

  def getIssueLabels(ownerRepo: OwnerRepo, issueNumber: Int, accessToken: String): Future[JsArray] = {
    val path = s"repos/$ownerRepo/issues/$issueNumber/labels"
    ws(path, accessToken).get().flatMap(okT[JsArray])
  }

  def applyLabel(ownerRepo: OwnerRepo, label: Label, issueNumber: Int, accessToken: String): Future[JsArray] = {
    val path = s"repos/$ownerRepo/issues/$issueNumber/labels"
    val json =  Json.arr(label.name)
    ws(path, accessToken).post(json).flatMap(okT[JsArray]).flatMap { jsArray =>
      // validate the label has the correct color
      val maybeColor = jsArray.value.find(_.\("name").as[String] == label.name).map(_.\("color").as[String])

      maybeColor match {
        case Some(color) if color != label.color =>
          updateLabel(ownerRepo, label, accessToken).flatMap(_ => getIssueLabels(ownerRepo, issueNumber, accessToken))
        case _ =>
          Future.successful(jsArray)
      }
    }
  }

  // github is lying to us here :
  // https://developer.github.com/v3/issues/labels/#remove-a-label-from-an-issue
  // Supposed to return Status: 204 No Content
  // but actually returns 200 : OK
  def removeLabel(ownerRepo: OwnerRepo, label: Label, issueNumber: Int, accessToken: String): Future[Unit] = {
    val path = s"repos/$ownerRepo/issues/$issueNumber/labels/${label.name}"
    ws(path, accessToken).delete().flatMap(ok).map(_ => Unit)
  }

  // todo: do not re-apply an existing label
  def toggleLabel(ownerRepo: OwnerRepo, newLabel: Label, oldLabel: Label, issueNumber: Int, accessToken: String): Future[Option[JsValue]] = {
    getIssueLabels(ownerRepo, issueNumber, accessToken).flatMap { json =>
      val issueLabels = json.value.map(_.\("name").as[String])

      val removeLabelFuture = if (issueLabels.contains(oldLabel.name)) {
        removeLabel(ownerRepo, oldLabel, issueNumber, accessToken)
      }
      else {
        Future.successful(())
      }

      removeLabelFuture.flatMap { _ =>
        if (!issueLabels.contains(newLabel.name)) {
          applyLabel(ownerRepo, newLabel, issueNumber, accessToken).map(Some(_))
        }
        else {
          Future.successful(None)
        }
      }
    }
  }

  def orgMembers(owner: Owner, accessToken: String): Future[Set[User]] = {
    val path = s"orgs/$owner/members"
    fetchPages(path, accessToken).map(_.as[Set[User]]).recoverWith {
      case _ =>
        // is this not an org?
        user(owner, accessToken).map(Set(_))
    }
  }

  def user(owner: Owner, accessToken: String): Future[User] = {
    val path = s"users/$owner"
    ws(path, accessToken).get().flatMap(okT[JsObject]).map(_.as[User])
  }

  def commit(ownerRepo: OwnerRepo, message: String, tree: String, parents: Set[String], maybeAuthor: Option[(String, String)], accessToken: String): Future[JsObject] = {
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

  def repoCommit(ownerRepo: OwnerRepo, sha: String, accessToken: String): Future[JsObject] = {
    val path = s"repos/$ownerRepo/commits/$sha"
    ws(path, accessToken).get().flatMap(okT[JsObject])
  }

  def updateGitRef(ownerRepo: OwnerRepo, sha: String, ref: String, accessToken: String): Future[JsObject] = {
    val path = s"repos/$ownerRepo/git/refs/$ref"
    val json = Json.obj(
      "sha" -> sha
    )

    ws(path, accessToken).patch(json).flatMap(okT[JsObject])
  }

  def repoCommits(ownerRepo: OwnerRepo, accessToken: String): Future[JsArray] = {
    val path = s"repos/$ownerRepo/commits"
    fetchPages(path, accessToken)
  }

  def pullRequestWithCommitsAndStatus(accessToken:String)(pullRequest: JsValue): Future[JsObject] = {
    val ownerRepo = (pullRequest \ "base" \ "repo").as[OwnerRepo]
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

  def createRepo(name: String, maybeOrg: Option[Owner] = None, autoInit: Boolean = false)(accessToken: String): Future[OwnerRepo] = {
    val path = maybeOrg.fold("user/repos")(org => s"orgs/$org/repos")

    val json = Json.obj(
      "name" -> name,
      "auto_init" -> autoInit
    )

    ws(path, accessToken).post(json).flatMap(createdT[JsObject]).map(_.as[OwnerRepo])
  }

  def repo(ownerRepo: OwnerRepo)(accessToken: String): Future[JsObject] = {
    ws(s"repos/$ownerRepo", accessToken).get().flatMap(okT[JsObject])
  }

  def deleteRepo(ownerRepo: OwnerRepo)(accessToken: String): Future[Unit] = {
    ws(s"repos/$ownerRepo", accessToken).delete().flatMap(nocontent).map(_ => Unit)
  }

  def forkRepo(ownerRepo: OwnerRepo)(accessToken: String): Future[JsObject] = {
    ws(s"repos/$ownerRepo/forks", accessToken).execute(HttpVerbs.POST).flatMap(statusT[JsObject](Status.ACCEPTED, _))
  }

  def getFile(ownerRepo: OwnerRepo, path: String, maybeRef: Option[String] = None)(accessToken: String): Future[JsObject] = {
    val queryString = maybeRef.fold(Map.empty[String, String])(ref => Map("ref" -> ref)).toSeq

    ws(s"repos/$ownerRepo/contents/$path", accessToken).withQueryStringParameters(queryString:_*).get().flatMap(okT[JsObject])
  }

  def editFile(ownerRepo: OwnerRepo, path: String, contents: String, commitMessage: String, sha: String, maybeBranch: Option[String] = None)(accessToken: String): Future[JsObject] = {
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

  def createPullRequest(ownerRepo: OwnerRepo, title: String, head: String, base: String, accessToken: String): Future[JsObject] = {
    val json = Json.obj(
      "title" -> title,
      "head" -> head,
      "base" -> base
    )

    ws(s"repos/$ownerRepo/pulls", accessToken).post(json).flatMap(createdT[JsObject])
  }

  def closePullRequest(ownerRepo: OwnerRepo, number: Int, accessToken: String): Future[JsObject] = {
    val json = Json.obj("state" -> "closed")
    ws(s"repos/$ownerRepo/pulls/$number", accessToken).patch(json).flatMap(okT[JsObject])
  }

  def createBranch(ownerRepo: OwnerRepo, name: String, sha: String, accessToken: String): Future[JsObject] = {
    val json = Json.obj(
      "ref" -> s"refs/heads/$name",
      "sha" -> sha
    )

    ws(s"repos/$ownerRepo/git/refs", accessToken).post(json).flatMap(createdT[JsObject])
  }

  private def pullRequestHasContributorAndState(user: User, state: String)(pullRequest: JsObject): Boolean = {
    val contributors = (pullRequest \ "commits").as[Set[Contributor]]
    val prState = (pullRequest \ "status" \ "state").as[String]

    contributors.contains(user) && (state == prState)
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
  private def pullRequestsNeedingValidationForAccessToken(signerGitHubId: User, repos: Seq[JsObject], accessToken: String): Future[Map[JsObject, String]] = {
    val repoNames = repos.map(_.as[OwnerRepo]).toSet

    for {
      allPullRequests <- Future.sequence(repoNames.map(ownerRepo => pullRequests(ownerRepo, accessToken, Some("open"))))
      pullRequestWithCommitsAndStatus <- Future.sequence(allPullRequests.flatMap(_.value).map(pullRequestWithCommitsAndStatus(accessToken)))
    } yield pullRequestWithCommitsAndStatus.filter(pullRequestHasContributorAndState(signerGitHubId, "failure")).map { pullRequest =>
      pullRequest -> accessToken
    }.toMap
  }

  def pullRequestsToBeValidated(signerGitHubId: User): Future[Map[JsObject, String]] = {
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

  def pullRequestUserCommitters(ownerRepo: OwnerRepo, prNumber: Int, sha: String, accessToken: String): Future[Set[Contributor]] = {
    val prCommitsFuture = pullRequestCommits(ownerRepo, prNumber, accessToken)

    prCommitsFuture.map { commits =>
      val onlyUserCommits = commits.value.filterNot { commit =>
        val maybeAuthorType = (commit \ "author" \ "type").asOpt[String]
        maybeAuthorType.contains("Bot")
      }

      onlyUserCommits.map(_.as[Contributor]).toSet
    }
  }

  def repoContributors(ownerRepo: OwnerRepo, accessToken: String): Future[Set[ContributorWithMetrics]] = {
    for {
      commits <- repoCommits(ownerRepo, accessToken)
    } yield {
      val contributorsWithCommits = commits.value.groupBy[Contributor](_.as[Contributor])

      contributorsWithCommits.map { case (contributor, contributorCommits) =>
        ContributorWithMetrics(contributor, contributorCommits.size)
      }.toSet
    }
  }

  def externalContributors(contributors: Set[Contributor], internalContributors: Set[Contributor]): Set[Contributor] = {
    contributors.diff(internalContributors)
  }

  def externalContributorsForPullRequest(ownerRepo: OwnerRepo, prNumber: Int, sha: String, accessToken: String): Future[Set[Contributor]] = {
    val pullRequestCommittersFuture = pullRequestUserCommitters(ownerRepo, prNumber, sha, accessToken)

    val orgMembersFuture = orgMembers(ownerRepo.owner, accessToken)

    for {
      pullRequestCommitters <- pullRequestCommittersFuture
      orgMembers <- orgMembersFuture
    } yield externalContributors(pullRequestCommitters, orgMembers.toSet[Contributor])

  }

  def committersWithoutClas(externalContributors: Set[Contributor])(clasForCommitters: Set[Contributor] => Future[Set[ClaSignature]]): Future[Set[Contributor]] = {
    for {
      clasForCommitters <- clasForCommitters(externalContributors)
    } yield {
      // todo: maybe check latest CLA version
      // todo: ability to exclude users with a given email address domain
      externalContributors.diff(clasForCommitters.map(claSignature => User(claSignature.contactGitHubId)))
    }
  }

  def commentWithoutDuplicate(ownerRepo: OwnerRepo, prNumber: Int, accessToken: String)(message: String): Future[Option[JsValue]] = {
    issueComments(ownerRepo, prNumber, accessToken).flatMap { comments =>
      val alreadyCommented = comments.value.exists(_.\("body").as[String] == message)
      if (!alreadyCommented) {
        commentOnIssue(ownerRepo, prNumber, message, accessToken).map(Some(_))
      }
      else {
        Future.successful(None)
      }
    }
  }

  def partitionContributorsInternalAndExternal[A <: Contributor](contributors: Set[A]): (Set[A], Set[A]) = {
    contributors.partition { contributor =>
      contributor.isInternal(maybeDomain)
    }
  }

  def missingClaComment(ownerRepo: OwnerRepo, prNumber: Int, sha: String, claUrl: String, users: Set[User], accessToken: String): Future[(Option[JsValue], Option[JsValue])] = {
    if (users.nonEmpty) {
      val usersToString: Set[User] => String = _.map(_.username).mkString("@", " @", "")

      val (internalUsers, externalUsers) = partitionContributorsInternalAndExternal(users)

      val maybeInternalMessage = if (internalUsers.isEmpty) None else Some {
        maybeInstructionsUrl.fold {
          messagesApi("cla.missing.internal-no-instructions", usersToString(internalUsers))
        } { instructionsUrl =>
          messagesApi("cla.missing.internal-with-instructions", usersToString(internalUsers), instructionsUrl)
        }
      }

      val maybeExternalMessage = if (externalUsers.isEmpty) None else Some {
        messagesApi("cla.missing", usersToString(externalUsers), orgName, claUrl)
      }

      val internalUsersCommentFuture = maybeInternalMessage.map(commentWithoutDuplicate(ownerRepo, prNumber, accessToken)).getOrElse(Future.successful(None))
      val externalUsersCommentFuture = maybeExternalMessage.map(commentWithoutDuplicate(ownerRepo, prNumber, accessToken)).getOrElse(Future.successful(None))

      for {
        internalUsersComment <- internalUsersCommentFuture
        externalUsersComment <- externalUsersCommentFuture
      } yield internalUsersComment -> externalUsersComment
    }
    else {
      Future.successful(None, None)
    }
  }

  def authorLoginNotFoundComment(ownerRepo: OwnerRepo, prNumber: Int, claUrl: String, statusUrl: String, unknownCommitters: Set[UnknownCommitter], accessToken: String): Future[(Option[JsValue], Option[JsValue], Option[JsValue])] = {
    if (unknownCommitters.nonEmpty) {

      val (internalCommitters, externalCommitters) = partitionContributorsInternalAndExternal(unknownCommitters)

      val (externalCommittersWithEmail, externalCommittersNoEmail) = externalCommitters.partition(_.maybeEmail.isDefined)

      val committersToString: Set[UnknownCommitter] => String = _.flatMap(_.toStringOpt()).mkString(" ")

      val maybeInternalMessage = if (internalCommitters.isEmpty) None else Some {
        messagesApi("cla.author-not-found.internal", committersToString(internalCommitters), statusUrl)
      }

      val maybeExternalCommittersWithEmailMessage = if (externalCommittersWithEmail.isEmpty) None else Some {
        messagesApi("cla.author-not-found", committersToString(externalCommittersWithEmail), orgName, claUrl)
      }

      val maybeExternalCommittersNoEmail = if (externalCommittersNoEmail.isEmpty) None else Some {
        messagesApi("cla.author-not-found.no-email")
      }

      for {
        internalCommittersComment <- maybeInternalMessage.map(commentWithoutDuplicate(ownerRepo, prNumber, accessToken)).getOrElse(Future.successful(None))
        externalCommittersWithEmailMessageComment <- maybeExternalCommittersWithEmailMessage.map(commentWithoutDuplicate(ownerRepo, prNumber, accessToken)).getOrElse(Future.successful(None))
        externalCommittersNoEmailComment <- maybeExternalCommittersNoEmail.map(commentWithoutDuplicate(ownerRepo, prNumber, accessToken)).getOrElse(Future.successful(None))
      } yield (internalCommittersComment, externalCommittersWithEmailMessageComment, externalCommittersNoEmailComment)
    }
    else {
      Future.successful((None, None, None))
    }
  }

  def updatePullRequestLabel(ownerRepo: OwnerRepo, prNumber: Int, hasExternalContributors: Boolean, hasMissingClas: Boolean, accessToken: String): Future[Option[JsValue]] = {
    if (hasExternalContributors) {
      if (hasMissingClas) {
        toggleLabel(ownerRepo, MissingLabel, SignedLabel, prNumber, accessToken)
      }
      else {
        toggleLabel(ownerRepo, SignedLabel, MissingLabel, prNumber, accessToken)
      }
    }
    else {
      // just in case there was a missing label on it
      removeLabel(ownerRepo, MissingLabel, prNumber, accessToken).map(_ => None).recover {
        case _: IncorrectResponseStatus => None
      }
    }
  }

  def validatePullRequest(pullRequest: JsObject, token: String, claUrl: String, statusUrl: String)(clasForCommitters: Set[Contributor] => Future[Set[ClaSignature]]): Future[ValidationResult] = {
    val (repo, prNumber) = pullRequestInfo(pullRequest)
    val sha = (pullRequest \ "pull_request" \ "head" \ "sha").as[String]

    def addComment(users: Set[User], unknownCommitters: Set[UnknownCommitter]): Future[(Option[JsValue], Option[JsValue], Option[JsValue], Option[JsValue], Option[JsValue])] = {
      val usersCommentFuture = missingClaComment(repo, prNumber, sha, claUrl, users, token)
      val unknownCommittersCommentFuture = authorLoginNotFoundComment(repo, prNumber, claUrl, statusUrl, unknownCommitters, token)

      for {
        (internalUsersComment, externalUsersComment) <- usersCommentFuture
        (internalCommittersComment, externalCommittersWithEmailMessageComment, externalCommittersNoEmailComment) <- unknownCommittersCommentFuture
      } yield (internalUsersComment, externalUsersComment, internalCommittersComment, externalCommittersWithEmailMessageComment, externalCommittersNoEmailComment)
    }

    def updateStatus(users: Set[User], unknownCommitters: Set[UnknownCommitter]): Future[JsObject] = {

      val (state, description) = (users.isEmpty, unknownCommitters.isEmpty) match {
        case (true, true) =>
          ("success", "All contributors have signed the CLA")
        case (false, true) =>
          ("failure", "One or more contributors need to sign the CLA")
        case _ =>
          ("error", "Commit authors must be associated with GitHub users")
      }

      createStatus(repo, sha, state, statusUrl, description, gitHubBotName, token)
    }

    for {
      _ <- createStatus(repo, sha, "pending", statusUrl, "The CLA verifier is running", gitHubBotName, token)
      externalContributors <- externalContributorsForPullRequest(repo, prNumber, sha, token)
      committersWithoutClas <- committersWithoutClas(externalContributors)(clasForCommitters)
      users = committersWithoutClas.collect { case user: User => user }
      unknownCommitters = committersWithoutClas.collect { case unknownCommitter: UnknownCommitter => unknownCommitter }
      _ <- updatePullRequestLabel(repo, prNumber, externalContributors.nonEmpty, committersWithoutClas.nonEmpty, token)
      _ <- addComment(users, unknownCommitters)
      status <- updateStatus(users, unknownCommitters)
    } yield (externalContributors, committersWithoutClas, status)
  }

  def validatePullRequests(pullRequests: Map[JsObject, String], claUrlF: (OwnerRepo, Int) => String, statusUrlF: (OwnerRepo, Int) => String)(clasForCommitters: Set[Contributor] => Future[Set[ClaSignature]]): Future[Iterable[ValidationResult]] = {
    Future.sequence {
      pullRequests.map { case (pullRequest, token) =>
        val (ownerRepo, prNum) = pullRequestInfo(pullRequest)
        validatePullRequest(pullRequest, token, claUrlF(ownerRepo, prNum), statusUrlF(ownerRepo, prNum))(clasForCommitters)
      }
    }
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

  type ValidationResult = (Set[GitHub.Contributor], Set[GitHub.Contributor], JsObject)

  case class OwnerRepo(owner: Owner, repo: Repo) {
    override def toString: String = owner.name + "/" + repo.name
  }

  object OwnerRepo {
    def apply(s: String): OwnerRepo = {
      assert(s.split("/").length == 2)
      lazy val owner = Owner(s.split("/").head)
      lazy val repo = Repo(s.split("/").last)
      OwnerRepo(owner, repo)
    }

    implicit val jsonReads: Reads[OwnerRepo] = (
      (__ \ "owner").read[Owner] ~
      Repo.jsonReads
    )(OwnerRepo.apply(_, _))

    implicit val queryStringBindable: QueryStringBindable[OwnerRepo] = {
      QueryStringBindable.bindableString.transform(OwnerRepo(_), _.toString)
    }

    implicit val pathBindable: PathBindable[OwnerRepo] = {
      PathBindable.bindableString.transform(OwnerRepo(_), _.toString)
    }
  }

  case class Owner(name: String) {
    override def toString: String = name
  }

  object Owner {
    implicit val jsonReads: Reads[Owner] = {
      (__ \ "organization" \ "login").read[String]
        .orElse((__ \ "account" \ "login").read[String])
        .orElse((__  \ "login").read[String])
        .map(Owner(_))
    }
    //implicit val jsonWrites: Writes[Owner] = Json.writes[Owner]
  }

  case class Repo(name: String) {
    override def toString: String = name
  }

  object Repo {
    implicit val jsonReads: Reads[Repo] = (__ \ "name").read[String].map(Repo(_))
  }

  def pullRequestInfo(prUrl: String): (OwnerRepo, Int) = {
    val url = new URL(prUrl)
    val parts = url.getPath.stripPrefix("/").split("/pull/")
    OwnerRepo(parts(0)) -> parts(1).toInt
  }

  def pullRequestInfo(pullRequest: JsObject): (OwnerRepo, Int) = {
    val repo = (pullRequest \ "pull_request" \ "base" \ "repo").as[OwnerRepo]
    val prNumber = (pullRequest \ "pull_request" \ "number").as[Int]

    repo -> prNumber
  }

  def pullRequestUrl(ownerRepo: OwnerRepo, prNum: Int): String = s"https://github.com/$ownerRepo/pull/$prNum"

  case class IncorrectResponseStatus(expectedStatusCode: Int, actualStatusCode: Int, uri: URI, message: String) extends Exception {
    override def getMessage: String = s"$uri - Expected status code $expectedStatusCode but got $actualStatusCode - $message"
  }

  case class InvalidResponseBody(body: String) extends Exception {
    override def getMessage: String = "Response body was not in the expected form"
  }

  sealed trait Contributor {
    val maybeName: Option[String]
    val maybeEmail: Option[String]
    def isInternal(maybeDomain: Option[String]): Boolean = maybeDomain.fold(false) { domain =>
      maybeEmail.exists(_.endsWith(s"@$domain"))
    }
  }

  case class User(username: String, maybeName: Option[String] = None, maybeEmail: Option[String] = None) extends Contributor {
    override def equals(o: Any): Boolean = o match {
      case user: User => username == user.username
      case _ => false
    }

    override def hashCode: Int = username.hashCode
  }

  object User {
    implicit val jsonReads: Reads[User] = (
      (__ \ "login").read[String] ~
      (__ \ "name").readNullable[String] ~
      (__ \ "email").readNullable[String]
    )(User.apply(_, _, _))
  }

  case class UnknownCommitter(maybeName: Option[String], maybeEmail: Option[String]) extends Contributor {

    def publicEmail(email: String): Option[String] = {
      def obfuscate(s: String): String = {
        // No need for this. We're not exposing anything not already public. Be safe anyway
        s.take(1) + "***"
        // s
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

  object Contributor {
    implicit val contributorReads: Reads[Contributor] = {
      val nameReads = (__ \ "commit" \ "author" \ "name").readNullable[String]
      val emailReads = (__ \ "commit" \ "author" \ "email").readNullable[String]
      val loginReads = (__ \ "author" \ "login").readNullable[String]

      loginReads.flatMap { maybeLogin =>
        maybeLogin.fold[Reads[Contributor]] {
          (nameReads ~ emailReads) (UnknownCommitter)
        } { login =>
          (Reads.pure(login) ~ nameReads ~ emailReads)(User.apply(_, _, _))
        }
      }
    }
  }

  case class AuthInfo(encAuthToken: String, user: User)

  case class ContributorWithMetrics(contributor: Contributor, numCommits: Int)

  sealed trait Label {
    val name: String
    val color: String
  }

  case object MissingLabel extends Label {
    override val name: String = "cla:missing"
    override val color: String = "c40d0d"
  }

  case object SignedLabel extends Label {
    override val name: String = "cla:signed"
    override val color: String = "5ebc41"
  }

  object Label {
    def findByName(name: String): Option[Label] = {
      if (name == MissingLabel.name) Some(MissingLabel)
      else if (name == SignedLabel.name) Some(SignedLabel)
      else None
    }
  }

}
