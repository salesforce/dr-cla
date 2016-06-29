package utils

import java.util.UUID

import modules.{Database, DatabaseMock}
import org.scalatestplus.play.{OneAppPerSuite, PlaySpec}
import play.api.Mode
import play.api.inject._
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.WSClient
import play.api.test.Helpers._

import scala.concurrent.ExecutionContext


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

  val wsClient = app.injector.instanceOf[WSClient]

  val gitHub = new GitHub(app.configuration, wsClient)(ExecutionContext.global)

  "GitHub.allRepos" must {
    "fetch all the repos with 9 pages" in {
      val repos = await(gitHub.userRepos("jamesward-test", gitHub.integrationToken, 1))
      repos.value.length must equal (9)
    }
    "fetch all the repos without paging" in {
      val repos = await(gitHub.userRepos("jamesward-test", gitHub.integrationToken, 7))
      repos.value.length must equal (9)
    }
    "fetch all the repos with 2 pages" in {
      val repos = await(gitHub.userRepos("jamesward-test", gitHub.integrationToken, 4))
      repos.value.length must equal (9)
    }
  }

  "GitHub.userInfo" must {
    "fetch the userInfo" in {
      val userInfo = await(gitHub.userInfo(gitHub.integrationToken))
      (userInfo \ "login").asOpt[String] must be ('defined)
    }
  }

  "GitHub.getPullRequest" must {
    "get a PR" in {
      val pr = await(gitHub.getPullRequest("foobar-test/asdf", 1, gitHub.integrationToken))
      (pr \ "id").as[Int] must equal (48517065)
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
      (commentCreate \ "id").asOpt[Int] must be ('defined)
    }
  }

  "GitHub.userOrgs" must {
    "include the orgs" in {
      val userOrgs = await(gitHub.userOrgs(gitHub.integrationToken))
      userOrgs.value.map(_.\("login").as[String]) must contain ("foobar-test")
    }
  }

  "GitHub.allRepos" must {
    "include everything" in {
      val repos = await(gitHub.allRepos(gitHub.integrationToken))
      repos.value.map(_.\("full_name").as[String]) must contain ("foobar-test/asdf")
    }
  }

  "GitHub.pullRequests" must {
    "get the pull requests" in {
      val pullRequests = await(gitHub.pullRequests("foobar-test/asdf", gitHub.integrationToken))
      pullRequests.value.length must be > 0
    }
  }

  "GitHub.commitStatus" must {
    "get the commit status" in {
      val commitStatus = await(gitHub.commitStatus("foobar-test/asdf", "cf47c487ba8e8d8444c77b9e661267675b6b04ce", gitHub.integrationToken))
      (commitStatus \ "state").as[String] must equal ("failure")
    }
  }

  "GitHub.issueComments" must {
    "get the issue comments" in {
      val issueComments = await(gitHub.issueComments("foobar-test/asdf", 1, gitHub.integrationToken))
      issueComments.value.length must be > 0
    }
  }

  "GitHub.getAllLabels" must {
    "get the issue labels" in {
      val issueLabels = await(gitHub.getAllLabels("foobar-test/asdf", gitHub.integrationToken))
      issueLabels.value.length must be > 0
    }
  }

  "GitHub.createLabel" must {
    "create new label" in {
      val newLabel = await(gitHub.createLabel("foobar-test/asdf", "foobar", "000000", gitHub.integrationToken))
      (newLabel \ "name").as[String] must equal ("foobar")
      (newLabel \ "color").as[String] must equal ("000000")
    }
  }

  "GitHub.applyLabel" must {
    "apply a label to issue" in {
      val appliedLabels = await(gitHub.applyLabel("foobar-test/asdf", "foobar", 8, gitHub.integrationToken))
      (appliedLabels.head.get \ "name").as[String] must equal ("foobar")
    }
  }

  "GitHub.getIssueLabels" must {
    "get labels on an issue" in {
      val issueLabels = await(gitHub.getIssueLabels("foobar-test/asdf", 8, gitHub.integrationToken))
      issueLabels.value.map(_.\("name").as[String]) must contain ("foobar")
    }
  }

  "GitHub.removeLabel" must {
    "remove a label from issue" in {
      val removedLabel = await(gitHub.removeLabel("foobar-test/asdf", "foobar", 8, gitHub.integrationToken))
      removedLabel must equal (())
    }
  }

  "GitHub.deleteLabel" must {
    "delete a label" in {
      val deletedLabel = await(gitHub.deleteLabel("foobar-test/asdf", "foobar", gitHub.integrationToken))
      deletedLabel must equal (())
    }
  }

  "GitHub.addOrgWebhook" must {
    "create an org Webhook" in {
      val uuid = UUID.randomUUID().toString
      val status = await(gitHub.addOrgWebhook("foobar-test", Seq("pull_request"), s"http://localhost:9000/$uuid", "json", gitHub.integrationToken))
      (status \ "active").as[Boolean] must be (true)
    }
  }

  "GitHub.orgWebhooks" must {
    "get the org webhooks" in {
      val webhooks = await(gitHub.orgWebhooks("foobar-test", gitHub.integrationToken))
      webhooks.value.length must be > 0
    }
  }

  "GitHub.userOrgMembership" must {
    "get the users org membership" in {
      val membership = await(gitHub.userOrgMembership("foobar-test", gitHub.integrationToken))
      (membership \ "role").asOpt[String] must be ('defined)
    }
  }

  "GitHub.orgMembers" must {
    "get the org members" in {
      val members = await(gitHub.orgMembers("foobar-test", gitHub.integrationToken))
      members.value.length must be > 0
    }
  }

  "GitHub.repoCommits" must {
    "get the repo commits" in {
      val commits = await(gitHub.repoCommits("foobar-test/asdf", gitHub.integrationToken))
      commits.value.length must be > 0
    }
  }

}
