package utils

import java.net.URL
import javax.inject.Inject

import play.api.Configuration
import play.api.http.{HeaderNames, MimeTypes, Status}
import play.api.libs.json.Reads._
import play.api.libs.json._
import play.api.libs.ws.{WSClient, WSRequest, WSResponse}
import play.api.mvc.Results.EmptyContent

import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

class GitHub @Inject() (configuration: Configuration, ws: WSClient) (implicit ec: ExecutionContext) {

  val clientId = configuration.getString("github.oauth.client-id").get
  val clientSecret = configuration.getString("github.oauth.client-secret").get
  val integrationToken = configuration.getString("github.token").get

  def ws(path: String, accessToken: String): WSRequest = {
    ws
      .url(s"https://api.github.com/$path")
      .withHeaders(
        HeaderNames.AUTHORIZATION -> s"token $accessToken",
        HeaderNames.ACCEPT -> "application/vnd.github.v3+json"
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

  // deals with pagination
  // todo: extract paging logic
  private def userOrOrgRepos(userOrOrg: Either[String, String], accessToken: String, pageSize: Int = 100): Future[JsArray] = {

    val path = userOrOrg match {
      case Left(user) => s"users/$user/repos"
      case Right(org) => s"orgs/$org/repos"
    }

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
    ws("user/orgs", accessToken).get().flatMap(ok[JsArray])
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

    ws(path, accessToken).get().flatMap(ok[JsValue])
  }

  def pullRequests(ownerRepo: String, accessToken: String): Future[JsArray] = {
    val path = s"repos/$ownerRepo/pulls"
    ws(path, accessToken).get().flatMap(ok[JsArray])
  }

  def createStatus(ownerRepo: String, sha: String, state: String, url: String, description: String, context: String, accessToken: String): Future[JsValue] = {
    val path = s"repos/$ownerRepo/statuses/$sha"

    val json = Json.obj(
      "state" -> state,
      "target_url" -> url,
      "description" -> description,
      "context" -> context
    )

    ws(path, accessToken).post(json).flatMap(created)
  }

  def pullRequestCommits(ownerRepo: String, pullRequestId: Int, accessToken: String): Future[JsArray] = {
    val path = s"repos/$ownerRepo/pulls/$pullRequestId/commits"

    ws(path, accessToken).get().flatMap(ok[JsArray])
  }

  def collaborators(ownerRepo: String, accessToken: String): Future[JsArray] = {
    val path = s"repos/$ownerRepo/collaborators"

    ws(path, accessToken).get().flatMap(ok[JsArray])
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
    ws(path, accessToken).get().flatMap(ok[JsValue])
  }

  def issueComments(ownerRepo: String, issueNumber: Int, accessToken: String): Future[JsArray] = {
    val path = s"repos/$ownerRepo/issues/$issueNumber/comments"
    ws(path, accessToken).get().flatMap(ok[JsArray])
  }

  def getAllLabels(ownerRepo: String, accessToken: String): Future[JsArray] = {
    val path = s"repos/$ownerRepo/labels"
    ws(path, accessToken).get().flatMap(ok[JsArray])
  }

  def createLabel(ownerRepo: String, name: String, color: String, accessToken: String): Future[JsValue] = {
    val path = s"repos/$ownerRepo/labels"

    val json = Json.obj(
      "name" -> name,
      "color" -> color
    )
    ws(path, accessToken).post(json).flatMap(created)
  }

  def deleteLabel(ownerRepo: String, name: String, accessToken: String): Future[Unit] = {
    val path = s"repos/$ownerRepo/labels/$name"
    ws(path, accessToken).delete().flatMap(nocontent)
  }

  def createLabels(ownerRepo: String, labels: Map[String,String], accessToken: String): Future[Seq[JsValue]] = {
    val labelCreateFutures = labels.map { labelColor =>
      createLabel(ownerRepo, labelColor._1, labelColor._2, accessToken)
    }
    Future.sequence(labelCreateFutures.toList)
  }

  def getIssueLabels(ownerRepo: String, issueNumber: Int, accessToken: String): Future[JsArray] = {
    val path = s"repos/$ownerRepo/issues/$issueNumber/labels"
    ws(path, accessToken).get().flatMap(ok[JsArray])
  }

  def applyLabel(ownerRepo: String, name: String, issueNumber: Int, accessToken: String): Future[JsArray] = {
    val path = s"repos/$ownerRepo/issues/$issueNumber/labels"
    val json =  Json.arr(name)
    ws(path, accessToken).post(json).flatMap(ok[JsArray])
  }

  def applyLabelSafe(ownerRepo: String, name: String, issueNumber: Int, accessToken: String): Future[JsArray] = {
    val issueLabelsFuture = getIssueLabels(ownerRepo, issueNumber, accessToken).map(_.value.map(_.\("name").as[String]).distinct.toList)
    issueLabelsFuture.flatMap { labels =>
      if (!labels.contains(name)) {
        applyLabel(ownerRepo, name, issueNumber, accessToken)
      } else {
        Future.successful(Json.arr())
      }
    }
  }

  // github is lying to us here :
  // https://developer.github.com/v3/issues/labels/#remove-a-label-from-an-issue
  // Supposed to return Status: 204 No Content
  // but actually returns 200 : OK
  def removeLabel(ownerRepo: String, name: String, issueNumber: Int, accessToken: String): Future[Unit] = {
    val path = s"repos/$ownerRepo/issues/$issueNumber/labels/$name"
    ws(path, accessToken).delete().flatMap { response =>
      response.status match {
        case Status.OK => Future.successful(Unit)
        case _ => Future.failed(new IllegalStateException(response.body))
      }
    }
  }

  def removeLabelSafe(ownerRepo: String, name: String, issueNumber: Int, accessToken: String): Future[Unit] = {
    val issueLabelsFuture = getIssueLabels(ownerRepo, issueNumber, accessToken).map(_.value.map(_.\("name").as[String]).distinct.toList)

    issueLabelsFuture.flatMap { labels =>
      if (labels.contains(name)) {
        removeLabel(ownerRepo, name, issueNumber, accessToken)
      }
      else {
        Future.successful(Unit)
      }
    }
  }

  def toggleLabelSafe(ownerRepo: String, newLabel: String, oldLabel: String, remove: Boolean, issueNumber: Int, accessToken: String): Future[JsValue] = {
    val removeLabelFuture = if (remove) {
      removeLabelSafe(ownerRepo, oldLabel, issueNumber, accessToken)
    }
    else {
      Future.successful(Unit)
    }

    val applyLabelSafeFuture = applyLabelSafe(ownerRepo, newLabel, issueNumber, accessToken)

    removeLabelFuture.flatMap(_ => applyLabelSafeFuture)
  }

  def orgWebhooks(org: String, accessToken: String): Future[JsArray] = {
    val path = s"orgs/$org/hooks"
    ws(path, accessToken).get().flatMap(ok[JsArray])
  }

  def userOrgMembership(org: String, accessToken: String): Future[JsObject] = {
    val path = s"user/memberships/orgs/$org"
    ws(path, accessToken).get().flatMap(ok[JsObject])
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
    ws(path, accessToken).get().flatMap(ok[JsArray])
  }

  def orgMembersAdd(org: String, username: String, accessToken: String): Future[JsObject] = {
    val path = s"orgs/$org/memberships/$username"
    val json = Json.obj("role" -> "admin")
    ws(path, accessToken).put(json).flatMap(ok[JsObject])
  }

  def activateOrgMembership(org: String, username: String, accessToken: String): Future[JsObject] = {
    val path = s"user/memberships/orgs/$org"
    val json = Json.obj("state" -> "active")
    ws(path, accessToken).patch(json).flatMap(ok[JsObject])
  }

  // todo: definitely will need paging
  def repoCommits(ownerRepo: String, accessToken: String): Future[JsArray] = {
    val path = s"repos/$ownerRepo/commits"
    ws(path, accessToken).get().flatMap(ok[JsArray])
  }

  private def ok[A](response: WSResponse)(implicit w: Reads[A]): Future[A] = status(Status.OK, response).flatMap { jsValue =>
    jsValue.asOpt[A].fold {
      Future.failed[A](new IllegalStateException("Data was not in the expected form"))
    } (Future.successful)
  }

  private def nocontent(response: WSResponse): Future[Unit] = {
    if (response.status == Status.NO_CONTENT) {
      Future.successful(Unit)
    } else {
      Future.failed(new IllegalStateException(response.body))
    }
  }

  // todo: ok with a JsValue default

  private def created(response: WSResponse): Future[JsValue] = status(Status.CREATED, response)

  private def status(statusCode: Int, response: WSResponse): Future[JsValue] = {
    if (response.status == statusCode) {
      Future.successful(response.json)
    } else {
      Future.failed(new IllegalStateException(response.body))
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

  case class Org(login: String, repos: Seq[Repo] = Seq.empty[Repo])

  object Org {
    implicit val jsonReads: Reads[Org] = (__ \ "login").read[String].map(Org(_))
  }

}
