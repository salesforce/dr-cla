package utils

import models.{ClaSignature, Contact}
import modules.{Database, DatabaseMock}
import org.joda.time.LocalDateTime
import org.scalatestplus.play.{OneAppPerSuite, PlaySpec}
import pdi.jwt.{JwtClaim, JwtJson}
import play.api.Mode
import play.api.inject._
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsArray, JsObject, Json}
import play.api.libs.ws.WSClient
import play.api.test.Helpers._

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Random, Success, Try}


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

  lazy val wsClient = app.injector.instanceOf[WSClient]

  lazy val gitHub = new GitHub(app.configuration, wsClient)(ExecutionContext.global)

  val testToken1 = sys.env("GITHUB_TEST_TOKEN1")
  val testOrg1 = sys.env("GITHUB_TEST_ORG1")
  val testToken2 = sys.env("GITHUB_TEST_TOKEN2")

  lazy val testLogin1 = (await(gitHub.userInfo(testToken1)) \ "login").as[String]
  lazy val testLogin2 = (await(gitHub.userInfo(testToken2)) \ "login").as[String]

  lazy val testIntegrationInstallationId1: Int = {
    val integrationInstallations = await(gitHub.integrationInstallations())

    integrationInstallations.value.find { json =>
      (json \ "account" \ "login").asOpt[String].contains(testLogin1)
    }.flatMap { json =>
      (json \ "id").asOpt[Int]
    }.getOrElse(throw new IllegalStateException(s"$testLogin1 must have the integration ${gitHub.integrationId} installed"))
  }

  // Poll until the repo has commits - So much gross
  @tailrec
  private def waitForCommits(ownerRepo: String, testToken1: String) {
    val repoCommits = Try(await(gitHub.repoCommits(ownerRepo, testToken1))).getOrElse(JsArray())

    if (repoCommits.value.isEmpty) {
      Thread.sleep(1000)
      waitForCommits(ownerRepo, testToken1)
    }
  }

  private def createRepo(): String = {
    val repoName = Random.alphanumeric.take(8).mkString
    val createRepoResult = await(gitHub.createRepo(repoName, None, true)(testToken1))
    val ownerRepo = (createRepoResult \ "full_name").as[String]

    waitForCommits(ownerRepo, testToken1)

    ownerRepo
  }

  private def createFork(): String = {
    waitForCommits(testRepo1, testToken2)

    val forkResult = await(gitHub.forkRepo(testRepo1)(testToken2))
    val forkOwnerRepo = (forkResult \ "full_name").as[String]

    waitForCommits(forkOwnerRepo, testToken2)

    forkOwnerRepo
  }

  def pullRequestInfo(pullRequest: JsObject): (String, Int) = {
    val ownerRepo = (pullRequest \ "pull_request" \ "base" \ "repo" \ "full_name").as[String]
    val pullRequestNum = (pullRequest \ "pull_request" \ "number").as[Int]
    (ownerRepo, pullRequestNum)
  }

  lazy val testRepo1 = createRepo()
  lazy val testRepo2 = createRepo()
  lazy val testRepo3 = createRepo()
  lazy val testFork = createFork()
  lazy val testPullRequests = {
    val testRepos2 = await(gitHub.userRepos(testLogin2, testToken2))

    // make sure testToken2 does not have access to the upstream repo
    testRepos2.value.find(_.\("full_name").asOpt[String].contains(testRepo1)) must be (None)

    val readme = await(gitHub.getFile(testFork, "README.md", testToken2))

    val maybeReadmeSha = (readme \ "sha").asOpt[String]

    maybeReadmeSha must be ('defined)

    val readmeSha = maybeReadmeSha.get

    val newContents = Random.alphanumeric.take(32).mkString
    val editResult = await(gitHub.editFile(testFork, "README.md", newContents, "Updated", readmeSha, testToken2))
    (editResult \ "commit").asOpt[JsObject] must be ('defined)

    // testToken2 create PR to testToken1
    val pullRequest = await(gitHub.createPullRequest(testRepo1, "Updates", s"$testLogin2:master", "master", testToken2))
    (pullRequest \ "id").asOpt[Int] must be ('defined)

    // sometimes it takes a bit for the PR creation to propagate
    Thread.sleep(1000)

    Map(Json.obj("pull_request" -> pullRequest) -> testToken1)
  }

  lazy val (testPullRequest, _) = testPullRequests.head
  lazy val testPullRequestOwnerRepo = (testPullRequest \ "pull_request" \ "base" \ "repo" \ "full_name").as[String]
  lazy val testPullRequestNum = (testPullRequest \ "pull_request" \ "number").as[Int]

  "GitHub.allRepos" must {
    "fetch all the repos with 10 pages" in {
      (testRepo1, testRepo2, testRepo3) // create 3 repos lazily
      val repos = await(gitHub.userRepos(testLogin1, testToken1, 1))
      repos.value.length must be >= 3
    }
    "fetch all the repos without paging" in {
      val repos = await(gitHub.userRepos(testLogin1, testToken1, Int.MaxValue))
      repos.value.length must be >= 3
    }
    "fetch all the repos with 2 pages" in {
      val repos = await(gitHub.userRepos(testLogin1, testToken1, 2))
      repos.value.length must be >= 3
    }
  }

  "GitHub.userInfo" must {
    "fetch the userInfo" in {
      val userInfo = await(gitHub.userInfo(testToken1))
      (userInfo \ "login").asOpt[String] must contain (testLogin1)
    }
  }

  "GitHub.getPullRequest" must {
    "get a PR" in {
      val pr = await(gitHub.getPullRequest(testPullRequestOwnerRepo, testPullRequestNum, testToken1))
      (pr \ "id").as[Int] must equal ((testPullRequest \ "pull_request" \ "id").as[Int])
    }
  }

  "GitHub.updatePullRequestStatus" must {
    "update a PR" in {
      val pr = await(gitHub.getPullRequest(testPullRequestOwnerRepo, testPullRequestNum, testToken1))
      val mergeState = (pr \ "mergeable_state").as[String]
      val sha = (pr \ "head" \ "sha").as[String]
      val state = if (mergeState == "clean") "pending" else "success"
      val statusCreate = await(gitHub.createStatus(testPullRequestOwnerRepo, sha, state, "https://salesforce.com", "This is only a test", "salesforce-cla:GitHubSpec", testToken1))
      (statusCreate \ "state").as[String] must equal (state)
    }
  }

  "GitHub.pullRequestCommits" must {
    "get the commits on a PR" in {
      val commits = await(gitHub.pullRequestCommits(testPullRequestOwnerRepo, testPullRequestNum, testToken1))
      commits.value.length must be > 0
    }
  }

  "GitHub.collaborators" must {
    "get the collaborators on a repo" in {
      val collaborators = await(gitHub.collaborators(testPullRequestOwnerRepo, testToken1))
      collaborators.value.length must be > 0
    }
  }

  "GitHub.commentOnIssue" must {
    "comment on an issue" in {
      val commentCreate = await(gitHub.commentOnIssue(testPullRequestOwnerRepo, testPullRequestNum, "This is only a test.", testToken1))
      (commentCreate \ "id").asOpt[Int] must be ('defined)
    }
  }

  "GitHub.userOrgs" must {
    "include the orgs" in {
      val userOrgs = await(gitHub.userOrgs(testToken1))
      userOrgs.value.map(_.\("login").as[String]) must contain (testOrg1)
    }
  }

  "GitHub.allRepos" must {
    "include everything" in {
      val repo = Random.alphanumeric.take(8).mkString
      val ownerRepo = (await(gitHub.createRepo(repo, Some(testOrg1))(testToken1)) \ "full_name").as[String]
      val repos = await(gitHub.allRepos(testToken1))
      repos.value.map(_.\("full_name").as[String]) must contain (ownerRepo)
      val deleteResult = await(gitHub.deleteRepo(ownerRepo)(testToken1))
      deleteResult must equal (())
    }
  }

  "GitHub.pullRequests" must {
    "get the pull requests" in {
      val pullRequestsInRepo = await(gitHub.pullRequests(testPullRequestOwnerRepo, testToken1))
      pullRequestsInRepo.value.length must be > 0
    }
  }

  "GitHub.commitStatus" must {
    "get the commit status" in {
      val sha = (testPullRequest \ "pull_request" \ "head" \ "sha").as[String]
      await(gitHub.createStatus(testPullRequestOwnerRepo, sha, "failure", "http://asdf.com", "asdf", "salesforce-cla:GitHubSpec", testToken1))
      val commitStatus = await(gitHub.commitStatus(testPullRequestOwnerRepo, sha, testToken1))
      (commitStatus \ "state").as[String] must equal ("failure")
    }
  }

  "GitHub.issueComments" must {
    "get the issue comments" in {
      await(gitHub.commentOnIssue(testPullRequestOwnerRepo, testPullRequestNum, "This is only a test.", testToken1))
      val issueComments = await(gitHub.issueComments(testPullRequestOwnerRepo, testPullRequestNum, testToken1))
      issueComments.value.length must be > 0
    }
  }

  "GitHub.getAllLabels" must {
    "get the issue labels" in {
      val issueLabels = await(gitHub.getAllLabels(testPullRequestOwnerRepo, testToken1))
      issueLabels.value.length must be > 0
    }
  }

  "GitHub.createLabel" must {
    "create new label" in {
      val newLabel = await(gitHub.createLabel(testPullRequestOwnerRepo, "foobar", "000000", testToken1))
      (newLabel \ "name").as[String] must equal ("foobar")
      (newLabel \ "color").as[String] must equal ("000000")
    }
  }

  "GitHub.applyLabel" must {
    "apply a label to issue" in {
      val appliedLabels = await(gitHub.applyLabel(testPullRequestOwnerRepo, "foobar", testPullRequestNum, testToken1))
      (appliedLabels.head.get \ "name").as[String] must equal ("foobar")
    }
  }

  "GitHub.getIssueLabels" must {
    "get labels on an issue" in {
      val issueLabels = await(gitHub.getIssueLabels(testPullRequestOwnerRepo, testPullRequestNum, testToken1))
      issueLabels.value.map(_.\("name").as[String]) must contain ("foobar")
    }
  }

  "GitHub.removeLabel" must {
    "remove a label from issue" in {
      val removedLabel = await(gitHub.removeLabel(testPullRequestOwnerRepo, "foobar", testPullRequestNum, testToken1))
      removedLabel must equal (())
    }
  }

  "GitHub.deleteLabel" must {
    "delete a label" in {
      val deletedLabel = await(gitHub.deleteLabel(testPullRequestOwnerRepo, "foobar", testToken1))
      deletedLabel must equal (())
    }
  }

  "GitHub.addOrgWebhook" must {
    "create an org Webhook" in {
      val status = await(gitHub.addOrgWebhook(testOrg1, Seq("pull_request"), "http://localhost:9000/foobar", "json", testToken1))
      (status \ "active").as[Boolean] must be (true)
    }
  }

  "GitHub.orgWebhooks" must {
    "get the org webhooks" in {
      val webhooks = await(gitHub.orgWebhooks(testOrg1, testToken1))
      webhooks.value.exists(_.\("config").\("url").as[String] == "http://localhost:9000/foobar") must be (true)
    }
  }

  "GitHub.addOrgWebhook" must {
    "delete an org Webhook" in {
      val webhooks = await(gitHub.orgWebhooks(testOrg1, testToken1))
      val deletes = webhooks.value.filter(_.\("config").\("url").as[String] == "http://localhost:9000/foobar").map { webhook =>
        val hookId = (webhook \ "id").as[Int]
        await(gitHub.deleteOrgWebhook(testOrg1, hookId, testToken1))
      }
      deletes.size must be > 0
    }
  }

  "GitHub.userOrgMembership" must {
    "get the users org membership" in {
      val membership = await(gitHub.userOrgMembership(testOrg1, testToken1))
      (membership \ "role").asOpt[String] must be ('defined)
    }
  }

  "GitHub.orgMembers" must {
    "get the org members" in {
      val members = await(gitHub.orgMembers(testOrg1, testToken1))
      members.value.length must be > 0
    }
  }

  "GitHub.repoCommits" must {
    "get the repo commits" in {
      val commits = await(gitHub.repoCommits(testPullRequestOwnerRepo, testToken1))
      commits.value.length must be > 0
    }
  }

  "GitHub.jwtEncode" must {
    "work" in {
      val claim = JwtClaim(subject = Some("test"))
      val encoded = gitHub.jwtEncode(claim)
      val Success(decoded) = JwtJson.decode(encoded, gitHub.integrationKeyPair.getPublic)

      decoded.subject must contain ("test")
    }
  }

  "GitHub.installationAccessTokens" must {
    "work" in {
      val result = await(gitHub.installationAccessTokens(testIntegrationInstallationId1))
      (result \ "token").asOpt[String] must be ('defined)
    }
  }

  "GitHub.integrationInstallations" must {
    "work" in {
      val integrations = await(gitHub.integrationInstallations())
      integrations.value.length must be > 0
    }
  }

  "GitHub.installationRepositories" must {
    "work" in {
      val token = (await(gitHub.installationAccessTokens(testIntegrationInstallationId1)) \ "token").as[String]
      val repos = await(gitHub.installationRepositories(token))
      repos.value.length must be > 0
    }
  }

  "GitHub.pullRequestsToBeValidatedViaDirectAccess" must {
    "work" in {
      val integrationLogin = await(gitHub.integrationLoginFuture)
      await(gitHub.addCollaborator(testPullRequestOwnerRepo, integrationLogin, testToken1))
      val pullRequestsToBeValidated = await(gitHub.pullRequestsToBeValidatedViaDirectAccess(testLogin2, gitHub.integrationToken))
      pullRequestsToBeValidated.size must be > 0
    }
  }

  "GitHub.pullRequestsToBeValidatedViaIntegrations" must {
    "work" in {
      val pullRequestsToBeValidated = await(gitHub.pullRequestsToBeValidatedViaIntegrations(testLogin2))
      pullRequestsToBeValidated.size must be > 0
    }
  }

  "GitHub.validatePullRequests" must {
    "work with integrations for pull requests with external contributors" in {

      val integrationtestToken1 = (await(gitHub.installationAccessTokens(testIntegrationInstallationId1)) \ "token").as[String]

      val pullRequestsViaIntegration = testPullRequests.mapValues(_ => integrationtestToken1)

      val validationResultsFuture = gitHub.validatePullRequests(pullRequestsViaIntegration, "http://asdf.com/") { _ =>
        Future.successful(Seq.empty[ClaSignature])
      }

      val validationResults = await(validationResultsFuture)
      validationResults.value.length must equal (1)
      (validationResults.value.head \ "user" \ "login").as[String].startsWith(await(gitHub.integrationLoginFuture)) must be (true)
    }
    "not comment on a pull request where there are no external contributors" in {
      val integrationtestToken1 = (await(gitHub.installationAccessTokens(testIntegrationInstallationId1)) \ "token").as[String]

      val pullRequestsViaIntegration = testPullRequests.mapValues(_ => integrationtestToken1)

      val validationResultsFuture = gitHub.validatePullRequests(pullRequestsViaIntegration, "http://asdf.com/") { _ =>
        Future.successful(Seq(ClaSignature(1, Contact(1, "Jon", "Doe", "jdoe@foo.com", testLogin2), new LocalDateTime(), "1.0")))
      }

      val validationResults = await(validationResultsFuture)
      validationResults.value.length must equal (1)
      (validationResults.value.head \ "state").as[String] must equal ("success")
    }
    "not comment twice on the same pull request" in {
      val integrationtestToken1 = (await(gitHub.installationAccessTokens(testIntegrationInstallationId1)) \ "token").as[String]

      val pullRequestsViaIntegration = testPullRequests.mapValues(_ => integrationtestToken1)

      await(gitHub.validatePullRequests(pullRequestsViaIntegration, "http://asdf.com/")(_ => Future.successful(Seq.empty[ClaSignature])))
      await(gitHub.validatePullRequests(pullRequestsViaIntegration, "http://asdf.com/")(_ => Future.successful(Seq.empty[ClaSignature])))

      val (pullRequest, testToken1) = pullRequestsViaIntegration.head
      val (ownerRepo, pullRequestNum) = pullRequestInfo(pullRequest)

      val issueComments = await(gitHub.issueComments(ownerRepo, pullRequestNum, testToken1))
      val integrationLogin = await(gitHub.integrationLoginFuture)
      issueComments.value.count(_.\("user").\("login").as[String].startsWith(integrationLogin)) must equal (1)
    }
  }

  it must {
    "cleanup" in {
      await(gitHub.deleteRepo(testRepo1)(testToken1))
      await(gitHub.deleteRepo(testRepo2)(testToken1))
      await(gitHub.deleteRepo(testRepo3)(testToken1))
      await(gitHub.deleteRepo(testFork)(testToken2))
    }
  }

}
