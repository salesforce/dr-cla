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
  val testOrg2 = sys.env("GITHUB_TEST_ORG2")
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

  lazy val testIntegrationInstallationIdOrg: Int = {
    val integrationInstallations = await(gitHub.integrationInstallations())

    integrationInstallations.value.find { json =>
      (json \ "account" \ "login").asOpt[String].contains(testOrg2)
    }.flatMap { json =>
      (json \ "id").asOpt[Int]
    }.getOrElse(throw new IllegalStateException(s"$testLogin1 must have the integration ${gitHub.integrationId} installed"))
  }

  lazy val testIntegrationToken1 = (await(gitHub.installationAccessTokens(testIntegrationInstallationId1)) \ "token").as[String]
  lazy val testIntegrationTokenOrg = (await(gitHub.installationAccessTokens(testIntegrationInstallationIdOrg)) \ "token").as[String]

  lazy val integrationLogin = await(gitHub.integrationLoginFuture)

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

  private def createOrgRepo(org: String): String = {
    val repoName = Random.alphanumeric.take(8).mkString
    val createRepoResult = await(gitHub.createRepo(repoName, Some(org), true)(testToken1))
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
  lazy val testOrg1Repo = createOrgRepo(testOrg1)
  lazy val testOrg2Repo = createOrgRepo(testOrg2)

  lazy val testExternalPullRequest = {
    val testRepos2 = await(gitHub.userRepos(testLogin2, testToken2))

    // make sure testToken2 does not have access to the upstream repo
    testRepos2.value.find(_.\("full_name").asOpt[String].contains(testRepo1)) must be (None)

    val readme = await(gitHub.getFile(testFork, "README.md", testToken2))

    val maybeReadmeSha = (readme \ "sha").asOpt[String]

    maybeReadmeSha must be ('defined)

    val readmeSha = maybeReadmeSha.get

    val newContents = Random.alphanumeric.take(32).mkString

    // external pull request
    val editResult = await(gitHub.editFile(testFork, "README.md", newContents, "Updated", readmeSha)(testToken2))
    (editResult \ "commit").asOpt[JsObject] must be ('defined)

    // testToken2 create PR to testToken1
    val externalPullRequest = await(gitHub.createPullRequest(testRepo1, "Updates", s"$testLogin2:master", "master", testToken2))
    (externalPullRequest \ "id").asOpt[Int] must be ('defined)

    Json.obj("pull_request" -> externalPullRequest)
  }

  lazy val testInternalPullRequest = {
    val newContents = Random.alphanumeric.take(32).mkString

    val readmeSha = (await(gitHub.getFile(testRepo1, "README.md", testToken1)) \ "sha").as[String]

    val commits = await(gitHub.repoCommits(testRepo1, testToken1))

    val sha = (commits.value.head \ "sha").as[String]

    val newBranch = await(gitHub.createBranch(testRepo1, "testing", sha, testToken1))

    val internalEditResult = await(gitHub.editFile(testRepo1, "README.md", newContents, "Updated", readmeSha, Some("testing"))(testToken1))
    (internalEditResult \ "commit").asOpt[JsObject] must be ('defined)

    val internalPullRequest = await(gitHub.createPullRequest(testRepo1, "Updates", "testing", "master", testToken1))
    (internalPullRequest \ "id").asOpt[Int] must be ('defined)

    Json.obj("pull_request" -> internalPullRequest)
  }

  lazy val testPullRequests = Map(testExternalPullRequest -> testToken1, testInternalPullRequest -> testToken1)

  lazy val testExternalPullRequestOwnerRepo = (testExternalPullRequest \ "pull_request" \ "base" \ "repo" \ "full_name").as[String]
  lazy val testExternalPullRequestNum = (testExternalPullRequest \ "pull_request" \ "number").as[Int]
  lazy val testExternalPullRequestSha = (testExternalPullRequest \ "pull_request" \ "head" \ "sha").as[String]

  lazy val testInternalPullRequestOwnerRepo = (testInternalPullRequest \ "pull_request" \ "base" \ "repo" \ "full_name").as[String]
  lazy val testInternalPullRequestNum = (testInternalPullRequest \ "pull_request" \ "number").as[Int]
  lazy val testInternalPullRequestSha = (testInternalPullRequest \ "pull_request" \ "head" \ "sha").as[String]

  "we" must {
    "setup stuff" in {
      testRepo1
      testRepo2
      testRepo3
      testFork
      testOrg1Repo
      testOrg2Repo
      testPullRequests
    }
    "verify the test structure" in {
      withClue(s"$testLogin2 must not be a collaborator on $testRepo1: ") {
        val testRepo1Collaborators = await(gitHub.collaborators(testRepo1, testToken1))
        testRepo1Collaborators.value.exists(_.\("login").as[String] == testLogin2) must be (false)
      }

      withClue(s"$integrationLogin must be a member of $testOrg1: ") {
        val orgMembers = await(gitHub.orgMembers(testOrg1, testToken1))
        orgMembers.value.exists(_.\("login").as[String] == integrationLogin) must be (true)
      }

      withClue(s"$integrationLogin must not be a member of $testOrg2: ") {
        val orgMembers = await(gitHub.orgMembers(testOrg2, testToken1))
        orgMembers.value.exists(_.\("login").as[String] == integrationLogin) must be (false)
      }

      withClue(s"$testLogin2 must be a private member of $testOrg2: ") {
        val allOrgMembers = await(gitHub.orgMembers(testOrg2, testToken1))
        allOrgMembers.value.exists(_.\("login").as[String] == testLogin2) must be (true)
        val publicOrgMembers = await(gitHub.orgMembers(testOrg2, testIntegrationToken1))
        publicOrgMembers.value.exists(_.\("login").as[String] == testLogin2) must be (false)
      }

      withClue(s"the integration must not be installed on $testOrg1: ") {
        val integrationInstallations = await(gitHub.integrationInstallations())
        integrationInstallations.value.exists(_.\("account").\("login").as[String] == testOrg1) must be (false)
      }

      withClue(s"the integration must be installed on $testOrg2: ") {
        val integrationInstallations = await(gitHub.integrationInstallations())
        integrationInstallations.value.exists(_.\("account").\("login").as[String] == testOrg2) must be (true)
      }
    }
  }

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
      val pr = await(gitHub.getPullRequest(testExternalPullRequestOwnerRepo, testExternalPullRequestNum, testToken1))
      (pr \ "id").as[Int] must equal ((testExternalPullRequest \ "pull_request" \ "id").as[Int])
    }
  }

  "GitHub.updatePullRequestStatus" must {
    "update a PR" in {
      val pr = await(gitHub.getPullRequest(testExternalPullRequestOwnerRepo, testExternalPullRequestNum, testToken1))
      val mergeState = (pr \ "mergeable_state").as[String]
      val sha = (pr \ "head" \ "sha").as[String]
      val state = if (mergeState == "clean") "pending" else "success"
      val statusCreate = await(gitHub.createStatus(testExternalPullRequestOwnerRepo, sha, state, "https://salesforce.com", "This is only a test", "salesforce-cla:GitHubSpec", testToken1))
      (statusCreate \ "state").as[String] must equal (state)
    }
  }

  "GitHub.pullRequestCommits" must {
    "get the commits on a PR" in {
      val commits = await(gitHub.pullRequestCommits(testExternalPullRequestOwnerRepo, testExternalPullRequestNum, testToken1))
      commits.value.length must be > 0
    }
  }

  "GitHub.collaborators" must {
    "get the collaborators on a repo" in {
      val collaborators = await(gitHub.collaborators(testExternalPullRequestOwnerRepo, testToken1))
      collaborators.value.find(_.\("login").as[String] == testLogin1) must be ('defined)
    }
    "work with the Integration" in {
      val collaborators = await(gitHub.collaborators(testRepo1, testIntegrationToken1))
      collaborators.value.find(_.\("login").as[String] == testLogin1) must be ('defined)
    }
    "see hidden collaborators via the Integration" in {
      val collaborators = await(gitHub.collaborators(testOrg2Repo, testIntegrationTokenOrg))
      collaborators.value.exists(_.\("login").as[String] == testLogin2) must be (true)
    }
  }

  "GitHub.commentOnIssue" must {
    "comment on an issue" in {
      val commentCreate = await(gitHub.commentOnIssue(testExternalPullRequestOwnerRepo, testExternalPullRequestNum, "This is only a test.", testToken1))
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
      val pullRequestsInRepo = await(gitHub.pullRequests(testExternalPullRequestOwnerRepo, testToken1))
      pullRequestsInRepo.value.length must be > 0
    }
  }

  "GitHub.commitStatus" must {
    "get the commit status" in {
      val sha = (testExternalPullRequest \ "pull_request" \ "head" \ "sha").as[String]
      await(gitHub.createStatus(testExternalPullRequestOwnerRepo, sha, "failure", "http://asdf.com", "asdf", "salesforce-cla:GitHubSpec", testToken1))
      val commitStatus = await(gitHub.commitStatus(testExternalPullRequestOwnerRepo, sha, testToken1))
      (commitStatus \ "state").as[String] must equal ("failure")
    }
  }

  "GitHub.issueComments" must {
    "get the issue comments" in {
      await(gitHub.commentOnIssue(testExternalPullRequestOwnerRepo, testExternalPullRequestNum, "This is only a test.", testToken1))
      val issueComments = await(gitHub.issueComments(testExternalPullRequestOwnerRepo, testExternalPullRequestNum, testToken1))
      issueComments.value.length must be > 0
    }
  }

  "GitHub.applyLabel" must {
    "apply a label to issue" in {
      val appliedLabels = await(gitHub.applyLabel(testExternalPullRequestOwnerRepo, "foobar", testExternalPullRequestNum, testToken1))
      appliedLabels.value.map(_.\("name").as[String]) must contain ("foobar")
    }

    "work with non-existant labels" in {
      val appliedLabels = await(gitHub.applyLabel(testExternalPullRequestOwnerRepo, "asdfasdf", testExternalPullRequestNum, testToken1))
      (appliedLabels.head.get \ "name").as[String] must equal ("asdfasdf")
    }

    "have the right color" in {
      val (labelName, labelColor) = gitHub.labels.head
      val appliedLabels = await(gitHub.applyLabel(testExternalPullRequestOwnerRepo, labelName, testExternalPullRequestNum, testToken1))
      val appliedLabel = appliedLabels.value.find(_.\("name").as[String] == labelName).get
      (appliedLabel \ "color").as[String] must equal (labelColor)
    }
  }

  "GitHub.getIssueLabels" must {
    "get labels on an issue" in {
      val issueLabels = await(gitHub.getIssueLabels(testExternalPullRequestOwnerRepo, testExternalPullRequestNum, testToken1))
      issueLabels.value.map(_.\("name").as[String]) must contain ("foobar")
    }
  }

  "GitHub.removeLabel" must {
    "remove a label from issue" in {
      val removedLabel = await(gitHub.removeLabel(testExternalPullRequestOwnerRepo, "foobar", testExternalPullRequestNum, testToken1))
      removedLabel must equal (())
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
      val commits = await(gitHub.repoCommits(testExternalPullRequestOwnerRepo, testToken1))
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
      await(gitHub.addCollaborator(testExternalPullRequestOwnerRepo, integrationLogin, testToken1))
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

  "GitHub.pullRequestCommitters" must {
    "work" in {
      val pullRequestCommitters = await(gitHub.pullRequestCommitters(testInternalPullRequestOwnerRepo, testInternalPullRequestNum, testInternalPullRequestSha, testIntegrationToken1))
      pullRequestCommitters must equal (Set(testLogin1))
    }
  }

  "GitHub.externalContributors" must {
    "not include repo collaborators" in {
      val externalContributors = await(gitHub.externalContributors(testInternalPullRequestOwnerRepo, testInternalPullRequestNum, testInternalPullRequestSha, testIntegrationToken1))
      externalContributors must be ('empty)
    }
  }

  "GitHub.validatePullRequests" must {
    "work with integrations for pull requests with only internal contributors" in {
      val pullRequestsViaIntegration = Map(testInternalPullRequest -> testIntegrationToken1)

      val validationResultsFuture = gitHub.validatePullRequests(pullRequestsViaIntegration, "http://asdf.com/") { _ =>
        Future.successful(Set.empty[ClaSignature])
      }

      val validationResults = await(validationResultsFuture)
      validationResults.size must equal (1)
      (validationResults.head \ "creator" \ "login").as[String].startsWith(integrationLogin) must be (true)
      (validationResults.head \ "state").as[String] must equal ("success")
    }
    "work with integrations for pull requests with external contributors" in {
      val pullRequestsViaIntegration = Map(testExternalPullRequest -> testIntegrationToken1)

      val validationResultsFuture = gitHub.validatePullRequests(pullRequestsViaIntegration, "http://asdf.com/") { _ =>
        Future.successful(Set.empty[ClaSignature])
      }

      val validationResults = await(validationResultsFuture)
      validationResults.size must equal (1)
      (validationResults.head \ "creator" \ "login").as[String].startsWith(integrationLogin) must be (true)
      (validationResults.head \ "state").as[String] must equal ("failure")
    }
    "not comment on a pull request when the external contributors have signed the CLA" in {
      val pullRequestsViaIntegration = Map(testExternalPullRequest -> testIntegrationToken1)

      val validationResultsFuture = gitHub.validatePullRequests(pullRequestsViaIntegration, "http://asdf.com/") { _ =>
        Future.successful(Set(ClaSignature(1, Contact(1, "Jon", "Doe", "jdoe@foo.com", testLogin2), new LocalDateTime(), "1.0")))
      }

      val validationResults = await(validationResultsFuture)
      validationResults.size must equal (1)
      (validationResults.head \ "state").as[String] must equal ("success")
    }
    "not comment twice on the same pull request" in {
      val pullRequestsViaIntegration = Map(testExternalPullRequest -> testIntegrationToken1)

      await(gitHub.validatePullRequests(pullRequestsViaIntegration, "http://asdf.com/")(_ => Future.successful(Set.empty[ClaSignature])))
      await(gitHub.validatePullRequests(pullRequestsViaIntegration, "http://asdf.com/")(_ => Future.successful(Set.empty[ClaSignature])))

      val issueComments = await(gitHub.issueComments(testExternalPullRequestOwnerRepo, testExternalPullRequestNum, testToken1))
      issueComments.value.count(_.\("user").\("login").as[String].startsWith(integrationLogin)) must equal (1)
    }
  }

  "we" must {
    "cleanup" in {
      await(gitHub.deleteRepo(testFork)(testToken2))
      await(gitHub.deleteRepo(testRepo1)(testToken1))
      await(gitHub.deleteRepo(testRepo2)(testToken1))
      await(gitHub.deleteRepo(testRepo3)(testToken1))
      await(gitHub.deleteRepo(testOrg1Repo)(testToken1))
      await(gitHub.deleteRepo(testOrg2Repo)(testToken1))
    }
  }

}
