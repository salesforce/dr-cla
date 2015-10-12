package utils

import java.net.URL
import javax.inject.Inject

import play.api.Application
import play.api.http.{HeaderNames, MimeTypes, Status}
import play.api.libs.json.{JsArray, JsValue}
import play.api.libs.ws.{WS, WSRequest, WSResponse}
import play.api.mvc.Results.EmptyContent

import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

class GitHub @Inject()(implicit app: Application, ec: ExecutionContext) {

  val clientId = app.configuration.getString("github.oauth.client-id").get
  val clientSecret = app.configuration.getString("github.oauth.client-secret").get

  def ws(path: String, accessToken: String): WSRequest = {
    WS
      .url(s"https://api.github.com/$path")
      .withHeaders(
        HeaderNames.AUTHORIZATION -> s"token $accessToken",
        HeaderNames.ACCEPT -> "application/vnd.github.v3+json"
      )
  }

  def accessToken(code: String): Future[String] = {
    val wsFuture = WS.url("https://github.com/login/oauth/access_token").withQueryString(
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
  def allRepos(path: String, accessToken: String, pageSize: Int = 100): Future[JsArray] = {

    import org.jboss.netty.handler.codec.http.QueryStringDecoder

    import collection.JavaConverters._

    implicit class Regex(sc: StringContext) {
      def r = new util.matching.Regex(sc.parts.mkString, sc.parts.tail.map(_ => "x"): _*)
    }

    def req(path: String, accessToken: String, page: Int, pageSize: Int): Future[WSResponse] = {
      ws(path, accessToken).withQueryString("page" -> page.toString, "per_page" -> pageSize.toString).get()
    }

    // get the first page
    req(path, accessToken, 1, pageSize).flatMap { response =>

      val firstPageRepos = response.json.as[JsArray]

      def urlToPage(urlString: String): Int = {
        val url = new URL(urlString)
        val params = new QueryStringDecoder(url.toURI.getRawQuery, false).getParameters.asScala.mapValues(_.asScala.toSeq).toMap
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

  def repos(accessToken: String): Future[JsArray] = {
    ws("user/orgs", accessToken).get().flatMap { orgsResponse =>
      val orgNames = (orgsResponse.json \\ "login").map(_.as[String])
      val orgReposFutures = orgNames.map { orgName =>
        allRepos(s"orgs/$orgName/repos", accessToken)
      }
      val userReposFuture = allRepos("user/repos", accessToken)
      Future.fold(orgReposFutures :+ userReposFuture)(JsArray()) { case (allRepos, orgRepos) =>
        allRepos ++ orgRepos.as[JsArray]
      }
    }
  }

  def userInfo(accessToken: String): Future[JsValue] = {
    ws("user", accessToken).get().flatMap { response =>
      response.status match {
        case Status.OK => Future.successful(response.json)
        case _ => Future.failed(new Exception(response.body))
      }
    }
  }

  def seqFutures[T, U](items: TraversableOnce[T])(f: T => Future[U]): Future[List[U]] = {
    items.foldLeft(Future.successful[List[U]](Nil)) {
      (futures, item) => futures.flatMap { values =>
        f(item).map(_ :: values)
      }
    } map (_.reverse)
  }

}