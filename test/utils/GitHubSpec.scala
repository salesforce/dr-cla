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

  "GitHub.allRepos" must {
    "fetch all the repos with 7 pages" in {
      val repos = await(gitHub.userRepos("jamesward-test", gitHub.integrationToken, 1))
      repos.value.length must equal (7)
    }
    "fetch all the repos without paging" in {
      val repos = await(gitHub.userRepos("jamesward-test", gitHub.integrationToken, 7))
      repos.value.length must equal (7)
    }
    "fetch all the repos with 2 pages" in {
      val repos = await(gitHub.userRepos("jamesward-test", gitHub.integrationToken, 4))
      repos.value.length must equal (7)
    }
  }

  "GitHub.userInfo" must {
    "fetch the userInfo" in {
      val userInfo = await(gitHub.userInfo(gitHub.integrationToken))
      (userInfo \ "login").asOpt[String] mustBe 'defined
    }
  }

  "GitHub.getPullRequest" must {
    "get a PR" in {
      val pr = await(gitHub.getPullRequest("foobar-test/asdf", 1, gitHub.integrationToken))
      (pr \ "id").as[Int] must equal (47550530)
    }
  }

  "GitHub.updatePullRequestStatus" must {
    "update a PR" in {
      // todo: there is a limit of 1000 statuses per sha and context within a Repository
      val pr = await(gitHub.getPullRequest("foobar-test/asdf", 1, gitHub.integrationToken))
      val mergeState = (pr \ "mergeable_state").as[String]
      val sha = (pr \ "head" \ "sha").as[String]
      val state = if (mergeState == "clean") "pending" else "success"
      val statusCreate = await(gitHub.createStatus("foobar-test/asdf", sha, state, "https://salesforce.com", "This is only a test", "salesforce-cla:GitHubSpec", gitHub.integrationToken))
      (statusCreate \ "state").as[String] must equal (state)
    }
  }

  "GitHub.pullRequestCommits" must {
    "get the commits on a PR" in {
      val commits = await(gitHub.pullRequestCommits("foobar-test/asdf", 1, gitHub.integrationToken))
      commits.value.length must be > 0
    }
  }

  "GitHub.collaborators" must {
    "get the collaborators on a repo" in {
      val collaborators = await(gitHub.collaborators("foobar-test/asdf", gitHub.integrationToken))
      collaborators.value.length must be > 0
    }
  }

  "GitHub.commentOnIssue" must {
    "comment on an issue" in {
      val commentCreate = await(gitHub.commentOnIssue("foobar-test/asdf", 1, "This is only a test.", gitHub.integrationToken))
      (commentCreate \ "id").asOpt[Int] mustBe 'defined
    }
  }

}