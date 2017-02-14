package utils

import java.util.UUID

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
    }.get
  }

  // Poll until the repo has commits - So much gross
  @tailrec
  private def waitForCommits(ownerRepo: String, accessToken: String) {
    val repoCommits = Try(await(gitHub.repoCommits(ownerRepo, accessToken))).getOrElse(JsArray())

    if (repoCommits.value.isEmpty) {
      Thread.sleep(1000)
      waitForCommits(ownerRepo, accessToken)
    }
  }

  def withRepo(testCode: String => Any): Unit = {
    val repoName = Random.alphanumeric.take(8).mkString
    val createRepoResult = await(gitHub.createRepo(repoName, None, true)(testToken1))
    val ownerRepo = (createRepoResult \ "full_name").as[String]

    waitForCommits(ownerRepo, testToken1)

    try {
      testCode(ownerRepo)
    }
    finally {
      await(gitHub.deleteRepo(ownerRepo)(testToken1))
    }
  }

  def withThreeRepos(testCode: => Any): Unit = {
    withRepo { repo1 =>
      withRepo { repo2 =>
        withRepo { repo3 =>
          testCode
        }
      }
    }
  }

  def withFork(testCode: (String, String) => Any): Unit = {
    withRepo { ownerRepo =>
      val forkResult = await(gitHub.forkRepo(ownerRepo)(testToken2))
      val forkOwnerRepo = (forkResult \ "full_name").as[String]

      waitForCommits(forkOwnerRepo, testToken2)

      try {
        testCode(ownerRepo, forkOwnerRepo)
      }
      finally {
        await(gitHub.deleteRepo(forkOwnerRepo)(testToken2))
      }
    }
  }

  def withPullRequests(testCode: Map[JsObject, String] => Any): Unit = {
    withFork { case (upstreamOwnerRepo, forkOwnerRepo) =>
      val testRepos2 = await(gitHub.userRepos(testLogin2, testToken2))

      // make sure testToken2 does not have access to the upstream repo
      testRepos2.value.find(_.\("full_name").asOpt[String].contains(upstreamOwnerRepo)) must be(None)

      val readme = await(gitHub.getFile(forkOwnerRepo, "README.md", testToken2))

      val maybeReadmeSha = (readme \ "sha").asOpt[String]

      maybeReadmeSha must be('defined)

      val readmeSha = maybeReadmeSha.get

      val newContents = Random.alphanumeric.take(32).mkString
      val editResult = await(gitHub.editFile(forkOwnerRepo, "README.md", newContents, "Updated", readmeSha, testToken2))
      (editResult \ "commit").asOpt[JsObject] must be('defined)

      // testToken2 create PR to testToken1
      val pullRequest = await(gitHub.createPullRequest(upstreamOwnerRepo, "Updates", s"$testLogin2:master", "master", testToken2))
      (pullRequest \ "id").asOpt[Int] must be('defined)

      val pullRequestsWithAccessToken = Map(Json.obj("pull_request" -> pullRequest) -> testToken1)

      testCode(pullRequestsWithAccessToken)
    }
  }

  def pullRequestInfo(pullRequest: JsObject): (String, Int) = {
    val ownerRepo = (pullRequest \ "pull_request" \ "base" \ "repo" \ "full_name").as[String]
    val pullRequestNum = (pullRequest \ "pull_request" \ "number").as[Int]
    (ownerRepo, pullRequestNum)
  }

  "GitHub.allRepos" must {
    "fetch all the repos with 10 pages" in withThreeRepos {
      val repos = await(gitHub.userRepos(testLogin1, testToken1, 1))
      repos.value.length must be >= 3
    }
    "fetch all the repos without paging" in withThreeRepos {
      val repos = await(gitHub.userRepos(testLogin1, testToken1, Int.MaxValue))
      repos.value.length must be >= 3
    }
    "fetch all the repos with 2 pages" in withThreeRepos {
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
    "get a PR" in withPullRequests { pullRequests =>
      val (pullRequest, accessToken) = pullRequests.head
      val (ownerRepo, pullRequestNum) = pullRequestInfo(pullRequest)
      val pr = await(gitHub.getPullRequest(ownerRepo, pullRequestNum, accessToken))
      (pr \ "id").as[Int] must equal ((pullRequest \ "pull_request" \ "id").as[Int])
    }
  }

  "GitHub.updatePullRequestStatus" must {
    "update a PR" in withPullRequests { pullRequests =>
      val (pullRequest, accessToken) = pullRequests.head
      val (ownerRepo, pullRequestNum) = pullRequestInfo(pullRequest)
      val pr = await(gitHub.getPullRequest(ownerRepo, pullRequestNum, accessToken))
      val mergeState = (pr \ "mergeable_state").as[String]
      val sha = (pr \ "head" \ "sha").as[String]
      val state = if (mergeState == "clean") "pending" else "success"
      val statusCreate = await(gitHub.createStatus(ownerRepo, sha, state, "https://salesforce.com", "This is only a test", "salesforce-cla:GitHubSpec", accessToken))
      (statusCreate \ "state").as[String] must equal (state)
    }
  }

  "GitHub.pullRequestCommits" must {
    "get the commits on a PR" in withPullRequests { pullRequests =>
      val (pullRequest, accessToken) = pullRequests.head
      val (ownerRepo, pullRequestNum) = pullRequestInfo(pullRequest)
      val commits = await(gitHub.pullRequestCommits(ownerRepo, pullRequestNum, accessToken))
      commits.value.length must be > 0
    }
  }

  "GitHub.collaborators" must {
    "get the collaborators on a repo" in withRepo { ownerRepo =>
      val collaborators = await(gitHub.collaborators(ownerRepo, testToken1))
      collaborators.value.length must be > 0
    }
  }

  "GitHub.commentOnIssue" must {
    "comment on an issue" in withPullRequests { pullRequests =>
      val (pullRequest, accessToken) = pullRequests.head
      val (ownerRepo, pullRequestNum) = pullRequestInfo(pullRequest)
      val commentCreate = await(gitHub.commentOnIssue(ownerRepo, pullRequestNum, "This is only a test.", accessToken))
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
    "get the pull requests" in withPullRequests { pullRequests =>
      val (pullRequest, accessToken) = pullRequests.head
      val (ownerRepo, pullRequestNum) = pullRequestInfo(pullRequest)
      val pullRequestsInRepo = await(gitHub.pullRequests(ownerRepo, testToken1))
      pullRequestsInRepo.value.length must be > 0
    }
  }

  "GitHub.commitStatus" must {
    "get the commit status" in withPullRequests { pullRequests =>
      val (pullRequest, accessToken) = pullRequests.head
      val (ownerRepo, pullRequestNum) = pullRequestInfo(pullRequest)
      val sha = (pullRequest \ "pull_request" \ "head" \ "sha").as[String]
      await(gitHub.createStatus(ownerRepo, sha, "failure", "http://asdf.com", "asdf", "salesforce-cla:GitHubSpec", accessToken))
      val commitStatus = await(gitHub.commitStatus(ownerRepo, sha, testToken1))
      (commitStatus \ "state").as[String] must equal ("failure")
    }
  }

  "GitHub.issueComments" must {
    "get the issue comments" in withPullRequests { pullRequests =>
      val (pullRequest, accessToken) = pullRequests.head
      val (ownerRepo, pullRequestNum) = pullRequestInfo(pullRequest)
      await(gitHub.commentOnIssue(ownerRepo, pullRequestNum, "This is only a test.", testToken1))
      val issueComments = await(gitHub.issueComments(ownerRepo, pullRequestNum, testToken1))
      issueComments.value.length must be > 0
    }
  }

  "GitHub.getAllLabels" must {
    "get the issue labels" in withRepo { ownerRepo =>
      val issueLabels = await(gitHub.getAllLabels(ownerRepo, testToken1))
      issueLabels.value.length must be > 0
    }
  }

  "GitHub.createLabel" must {
    "create new label" in withRepo { ownerRepo =>
      val newLabel = await(gitHub.createLabel(ownerRepo, "foobar", "000000", testToken1))
      (newLabel \ "name").as[String] must equal ("foobar")
      (newLabel \ "color").as[String] must equal ("000000")
    }
  }

  "GitHub.applyLabel" must {
    "apply a label to issue" in withPullRequests { pullRequests =>
      val (pullRequest, accessToken) = pullRequests.head
      val (ownerRepo, pullRequestNum) = pullRequestInfo(pullRequest)
      val issueLabels = await(gitHub.getAllLabels(ownerRepo, testToken1))
      val issueLabel = (issueLabels.value.head \ "name").as[String]
      val appliedLabels = await(gitHub.applyLabel(ownerRepo, issueLabel, pullRequestNum, testToken1))
      (appliedLabels.head.get \ "name").as[String] must equal (issueLabel)
    }
  }

  "GitHub.getIssueLabels" must {
    "get labels on an issue" in withPullRequests { pullRequests =>
      val (pullRequest, accessToken) = pullRequests.head
      val (ownerRepo, pullRequestNum) = pullRequestInfo(pullRequest)
      val allLabels = await(gitHub.getAllLabels(ownerRepo, testToken1))
      val issueLabel = (allLabels.value.head \ "name").as[String]
      val appliedLabels = await(gitHub.applyLabel(ownerRepo, issueLabel, pullRequestNum, testToken1))
      val issueLabels = await(gitHub.getIssueLabels(ownerRepo, pullRequestNum, testToken1))
      (issueLabels.value.head \ "name").as[String] must equal (issueLabel)
    }
  }

  "GitHub.removeLabel" must {
    "remove a label from issue" in withPullRequests { pullRequests =>
      val (pullRequest, accessToken) = pullRequests.head
      val (ownerRepo, pullRequestNum) = pullRequestInfo(pullRequest)
      val allLabels = await(gitHub.getAllLabels(ownerRepo, testToken1))
      val issueLabel = (allLabels.value.head \ "name").as[String]
      val appliedLabels = await(gitHub.applyLabel(ownerRepo, issueLabel, pullRequestNum, testToken1))
      val removedLabel = await(gitHub.removeLabel(ownerRepo, issueLabel, pullRequestNum, testToken1))
      removedLabel must equal (())
    }
  }

  "GitHub.deleteLabel" must {
    "delete a label" in withPullRequests { pullRequests =>
      val (pullRequest, accessToken) = pullRequests.head
      val (ownerRepo, pullRequestNum) = pullRequestInfo(pullRequest)
      val newLabel = await(gitHub.createLabel(ownerRepo, "foobar", "000000", testToken1))
      val deletedLabel = await(gitHub.deleteLabel(ownerRepo, "foobar", testToken1))
      deletedLabel must equal (())
    }
  }

  "GitHub.addOrgWebhook" must {
    "create an org Webhook" in {
      val uuid = UUID.randomUUID().toString
      val status = await(gitHub.addOrgWebhook(testOrg1, Seq("pull_request"), s"http://localhost:9000/$uuid", "json", testToken1))
      (status \ "active").as[Boolean] must be (true)
      val hookId = (status \ "id").as[Int]
      val delete = await(gitHub.deleteOrgWebhook(testOrg1, hookId, testToken1))
      delete must equal (())
    }
  }

  "GitHub.orgWebhooks" must {
    "get the org webhooks" in {
      val uuid = UUID.randomUUID().toString
      val status = await(gitHub.addOrgWebhook(testOrg1, Seq("pull_request"), s"http://localhost:9000/$uuid", "json", testToken1))
      val webhooks = await(gitHub.orgWebhooks(testOrg1, testToken1))
      webhooks.value.length must be > 0
      val hookId = (status \ "id").as[Int]
      val delete = await(gitHub.deleteOrgWebhook(testOrg1, hookId, testToken1))
      delete must equal (())
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
    "get the repo commits" in withRepo { ownerRepo =>
      val commits = await(gitHub.repoCommits(ownerRepo, testToken1))
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

  "GitHub.pullRequestsToBeValidated" must {
    "work" in withPullRequests { pullRequests =>
      val (pullRequest, accessToken) = pullRequests.head
      val (ownerRepo, pullRequestNum) = pullRequestInfo(pullRequest)
      val pullRequestsToBeValidated = await(gitHub.pullRequestsToBeValidated(testLogin2, gitHub.integrationToken))
      pullRequestsToBeValidated must not be null
    }
  }

  "GitHub.validatePullRequests" must {
    "work with integrations for pull requests with external contributors" in withPullRequests { pullRequests =>

      val integrationAccessToken = (await(gitHub.installationAccessTokens(testIntegrationInstallationId1)) \ "token").as[String]

      val pullRequestsViaIntegration = pullRequests.mapValues(_ => integrationAccessToken)

      val validationResultsFuture = gitHub.validatePullRequests(pullRequestsViaIntegration, "http://asdf.com/") { _ =>
        Future.successful(Seq.empty[ClaSignature])
      }

      val validationResults = await(validationResultsFuture)
      validationResults.value.length must equal (1)
      (validationResults.value.head \ "user" \ "login").as[String].startsWith(await(gitHub.integrationLoginFuture)) must be (true)
    }
    "not comment on a pull request where there are no external contributors" in withPullRequests { pullRequests =>
      val integrationAccessToken = (await(gitHub.installationAccessTokens(testIntegrationInstallationId1)) \ "token").as[String]

      val pullRequestsViaIntegration = pullRequests.mapValues(_ => integrationAccessToken)

      val validationResultsFuture = gitHub.validatePullRequests(pullRequestsViaIntegration, "http://asdf.com/") { _ =>
        Future.successful(Seq(ClaSignature(1, Contact(1, "Jon", "Doe", "jdoe@foo.com", testLogin2), new LocalDateTime(), "1.0")))
      }

      val validationResults = await(validationResultsFuture)
      validationResults.value.length must equal (1)
      (validationResults.value.head \ "state").as[String] must equal ("success")
    }
    "not comment twice on the same pull request" in withPullRequests { pullRequests =>
      val integrationAccessToken = (await(gitHub.installationAccessTokens(testIntegrationInstallationId1)) \ "token").as[String]

      val pullRequestsViaIntegration = pullRequests.mapValues(_ => integrationAccessToken)

      await(gitHub.validatePullRequests(pullRequestsViaIntegration, "http://asdf.com/")(_ => Future.successful(Seq.empty[ClaSignature])))
      await(gitHub.validatePullRequests(pullRequestsViaIntegration, "http://asdf.com/")(_ => Future.successful(Seq.empty[ClaSignature])))

      val (pullRequest, accessToken) = pullRequestsViaIntegration.head
      val (ownerRepo, pullRequestNum) = pullRequestInfo(pullRequest)

      val issueComments = await(gitHub.issueComments(ownerRepo, pullRequestNum, accessToken))
      issueComments.value.length must equal (1)
    }
  }

}
