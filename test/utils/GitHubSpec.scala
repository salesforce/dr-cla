package utils

import modules.{Database, DatabaseMock}
import org.scalatestplus.play.{OneAppPerSuite, PlaySpec}
import play.api.Mode
import play.api.inject._
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsArray, JsString, Json}
import play.api.libs.ws.{WS, WSAuthScheme}
import play.api.test.Helpers._

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global


class GitHubSpec extends PlaySpec with OneAppPerSuite {

  override implicit lazy val app = new GuiceApplicationBuilder()
    .overrides(bind[Database].to[DatabaseMock])
    .configure(
      Map(
        "play.modules.disabled" -> Seq("org.flywaydb.play.PlayModule", "modules.DatabaseModule")
      )
    )
    .in(Mode.Test)
    .build()

  val gitHub = new GitHub()(app, ExecutionContext.global)

  val clientId = app.configuration.getString("github.oauth.client-id").get
  val clientSecret = app.configuration.getString("github.oauth.client-secret").get

  val gitHubUsername = sys.env("GITHUB_USERNAME")
  val gitHubPassword = sys.env("GITHUB_PASSWORD")

  val accessToken = await {
    WS.url("https://api.gitHub.com/authorizations")
      .withAuth(gitHubUsername, gitHubPassword, WSAuthScheme.BASIC)
      .post {
        Json.obj(
          "scopes" -> JsArray(Seq(JsString("public_repo"))),
          "client_id" -> JsString(clientId),
          "client_secret" -> JsString(clientSecret)
        )
      } map { response =>
        (response.json \ "token").as[String]
      }
  }

  "Github.allRepos" must {
    "fetch all the repos with 7 pages" in {
      val repos = await(gitHub.allRepos("user/repos", accessToken, 1))
      repos.value.length must equal (7)
    }
    "fetch all the repos without paging" in {
      val repos = await(gitHub.allRepos("user/repos", accessToken, 7))
      repos.value.length must equal (7)
    }
    "fetch all the repos with 2 pages" in {
      val repos = await(gitHub.allRepos("user/repos", accessToken, 4))
      repos.value.length must equal (7)
    }
  }

  "Github.userInfo" must {
    "fetch the userInfo" in {
      val userInfo = await(gitHub.userInfo(accessToken))
      (userInfo \ "login").as[String] must equal ("jamesward-test")
    }
  }

}