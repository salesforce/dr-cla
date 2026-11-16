/*
 * Copyright (c) 2018-2026, Salesforce.com
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import java.io.StringWriter
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.util.concurrent.ConcurrentLinkedQueue

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import modules.{Database, DatabaseMock}
import akka.actor.ActorSystem
import akka.stream.Materializer
import com.typesafe.config.ConfigFactory
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.play.PlaySpec
import play.api.inject._
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json._
import play.api.libs.ws.ahc.{AhcWSClient, AhcWSClientConfigFactory}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.api.{Application, Configuration, Mode}
import utils.GitHub._

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

// A fake GitHub on a local port, so these specs need no GitHub tokens and see exactly which requests were made.
object GitHubFakeApiSpec {
  case class Req(method: String, path: String, query: String, authorization: String)

  case class Reply(status: Int, body: JsValue, headers: Seq[(String, String)] = Seq.empty)

  class FakeGitHub(respond: Req => Option[Reply]) {
    // "METHOD /path" in the order the requests arrived
    val requests = new ConcurrentLinkedQueue[String]()

    private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)

    server.createContext("/", (exchange: HttpExchange) => {
      val uri = exchange.getRequestURI
      val req = Req(
        exchange.getRequestMethod,
        uri.getPath,
        Option(uri.getRawQuery).getOrElse(""),
        Option(exchange.getRequestHeaders.getFirst("Authorization")).getOrElse("")
      )
      requests.add(s"${req.method} ${req.path}")
      exchange.getRequestBody.readAllBytes()

      val reply = try {
        respond(req).getOrElse(Reply(404, Json.obj("message" -> "Not Found")))
      } catch {
        case e: Exception => Reply(500, Json.obj("message" -> e.toString))
      }

      val bytes = Json.stringify(reply.body).getBytes("UTF-8")
      exchange.getResponseHeaders.add("Content-Type", "application/json")
      reply.headers.foreach { case (name, value) => exchange.getResponseHeaders.add(name, value) }
      exchange.sendResponseHeaders(reply.status, bytes.length.toLong)
      exchange.getResponseBody.write(bytes)
      exchange.close()
    })

    server.start()

    def url: String = s"http://127.0.0.1:${server.getAddress.getPort}"

    def seen: List[String] = requests.asScala.toList

    def stop(): Unit = server.stop(0)
  }

  // RSA PKCS#1 PEM, the shape GitHub gives out for an app's private key
  lazy val privateKeyPem: String = {
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val writer = new StringWriter()
    val pemWriter = new JcaPEMWriter(writer)
    pemWriter.writeObject(generator.generateKeyPair().getPrivate)
    pemWriter.close()
    writer.toString
  }

  def installation(id: Int, suspended: Boolean = false): JsObject = Json.obj(
    "id" -> id,
    "account" -> Json.obj("login" -> s"org$id"),
    "repository_selection" -> "all",
    "suspended_at" -> (if (suspended) JsString("2024-01-01T00:00:00Z") else JsNull)
  )
}

class GitHubFakeApiSpec extends PlaySpec with BeforeAndAfterAll {
  import GitHubFakeApiSpec._

  // only reference.conf, because application.conf needs the production environment variables
  private val referenceConfig = ConfigFactory.defaultReference()

  implicit val system: ActorSystem = ActorSystem("github-fake-api-spec", referenceConfig)
  implicit val materializer: Materializer = Materializer.matFromSystem(system)
  lazy val wsClient = AhcWSClient(AhcWSClientConfigFactory.forConfig(referenceConfig))

  // application.conf reads these from the environment, which a test doesn't have. Play and Quill both load that
  // file themselves, so system properties are the one way to give them a value, for this suite only.
  private val environmentSettings = Map(
    "GITHUB_CLIENT_ID" -> "client-id",
    "GITHUB_CLIENT_SECRET" -> "client-secret",
    "GITHUB_INTEGRATION_ID" -> "1",
    "GITHUB_INTEGRATION_SLUG" -> "slug",
    "GITHUB_INTEGRATION_CLIENT_ID" -> "integration-client-id",
    "GITHUB_INTEGRATION_CLIENT_SECRET" -> "integration-client-secret",
    "GITHUB_INTEGRATION_PRIVATE_KEY" -> privateKeyPem
  )

  override def beforeAll(): Unit = {
    environmentSettings.foreach { case (name, value) => System.setProperty(name, value) }
    ConfigFactory.invalidateCaches()
  }

  override def afterAll(): Unit = {
    environmentSettings.keys.foreach(System.clearProperty)
    ConfigFactory.invalidateCaches()
    wsClient.close()
    system.terminate()
  }

  val appSettings: Map[String, Any] = Map(
    "github.oauth.client-id" -> "client-id",
    "github.oauth.client-secret" -> "client-secret",
    "github.integration.id" -> "1",
    "github.integration.slug" -> "slug",
    "github.integration.client-id" -> "integration-client-id",
    "github.integration.client-secret" -> "integration-client-secret",
    "github.integration.private-key" -> privateKeyPem
  )

  def gitHubFor(fake: FakeGitHub): GitHub = {
    val configuration = Configuration.from(appSettings ++ Map(
      "github.botname" -> "bot",
      "app.organization.name" -> "org",
      "github.api-url" -> fake.url
    ))
    new GitHub(configuration, wsClient, stubMessagesApi())(ExecutionContext.global)
  }

  def appFor(fake: FakeGitHub): Application = {
    new GuiceApplicationBuilder()
      .overrides(bind[Database].to[DatabaseMock])
      .configure(
        "play.modules.disabled" -> Seq("org.flywaydb.play.PlayModule", "modules.DatabaseModule"),
        "github.api-url" -> fake.url
      )
      .in(Mode.Test)
      .build()
  }

  def withFake[T](respond: Req => Option[Reply])(block: FakeGitHub => T): T = {
    val fake = new FakeGitHub(respond)
    try block(fake) finally fake.stop()
  }

  "GitHub.repoInstallation" must {
    "ask for the installation of the one repo and nothing else" in {
      withFake {
        case Req("GET", "/repos/org/repo/installation", _, _) => Some(Reply(200, Json.obj("id" -> 7)))
        case _ => None
      } { fake =>
        val json = await(gitHubFor(fake).repoInstallation(OwnerRepo(Owner("org"), Repo("repo"))))
        (json \ "id").as[Int] must equal (7)
        fake.seen must equal (List("GET /repos/org/repo/installation"))
      }
    }
    "fail with AppNotInstalled on a 404" in {
      withFake(_ => None) { fake =>
        val ownerRepo = OwnerRepo(Owner("org"), Repo("repo"))
        val e = intercept[AppNotInstalled] {
          await(gitHubFor(fake).repoInstallation(ownerRepo))
        }
        e.ownerRepo must equal (ownerRepo)
      }
    }
    "not mistake another failure for a missing installation" in {
      withFake(_ => Some(Reply(500, Json.obj("message" -> "boom")))) { fake =>
        intercept[IncorrectResponseStatus] {
          await(gitHubFor(fake).repoInstallation(OwnerRepo(Owner("org"), Repo("repo"))))
        }.actualStatusCode must equal (500)
      }
    }
  }

  "GitHub.integrationInstallations" must {
    "follow the Link header to the later pages" in {
      lazy val fake: FakeGitHub = new FakeGitHub({
        case Req("GET", "/app/installations", query, _) if query.contains("page=2") =>
          Some(Reply(200, Json.arr(installation(2))))
        case Req("GET", "/app/installations", _, _) =>
          val link = s"""<${fake.url}/app/installations?per_page=100&page=2>; rel="next", <${fake.url}/app/installations?per_page=100&page=2>; rel="last""""
          Some(Reply(200, Json.arr(installation(1)), Seq("Link" -> link)))
        case _ => None
      })

      try {
        val installations = await(gitHubFor(fake).integrationInstallations())
        installations.value.map(i => (i \ "id").as[Int]).toSet must equal (Set(1, 2))
      } finally fake.stop()
    }
  }

  // A list endpoint that answers with one item per page, across two pages
  def twoPages(path: String, first: JsObject, second: JsObject)(fake: => FakeGitHub): Req => Option[Reply] = {
    case Req("GET", `path`, query, _) if query.contains("page=2") =>
      Some(Reply(200, Json.arr(second)))
    case Req("GET", `path`, _, _) =>
      val link = s"""<${fake.url}$path?per_page=100&page=2>; rel="next", <${fake.url}$path?per_page=100&page=2>; rel="last""""
      Some(Reply(200, Json.arr(first), Seq("Link" -> link)))
    case _ => None
  }

  "GitHub.pullRequestCommits" must {
    "follow the Link header to the later pages" in {
      lazy val fake: FakeGitHub = new FakeGitHub(twoPages("/repos/org/repo/pulls/1/commits", Json.obj("sha" -> "a"), Json.obj("sha" -> "b"))(fake))

      try {
        val commits = await(gitHubFor(fake).pullRequestCommits(OwnerRepo(Owner("org"), Repo("repo")), 1, "tok"))
        commits.value.map(c => (c \ "sha").as[String]) must equal (Seq("a", "b"))
      } finally fake.stop()
    }
  }

  "GitHub.issueComments" must {
    "follow the Link header to the later pages" in {
      lazy val fake: FakeGitHub = new FakeGitHub(twoPages("/repos/org/repo/issues/1/comments", Json.obj("body" -> "a"), Json.obj("body" -> "b"))(fake))

      try {
        val comments = await(gitHubFor(fake).issueComments(OwnerRepo(Owner("org"), Repo("repo")), 1, "tok"))
        comments.value.map(c => (c \ "body").as[String]) must equal (Seq("a", "b"))
      } finally fake.stop()
    }
  }

  // Installations 1..6. 2 is suspended, 3 refuses to hand out a token, 1, 4 and 6 have a failing PR of the signer.
  val signer = User("signer")
  val suspendedIds = Set(2)
  val brokenIds = Set(3)
  val withFailingPr = Set(1, 4, 6)

  def scanReplies(req: Req): Option[Reply] = {
    val TokenPath = """/app/installations/(\d+)/access_tokens""".r
    val Pulls = """/repos/org(\d+)/repo\d+/pulls""".r
    val Commits = """/repos/org(\d+)/repo\d+/pulls/1/commits""".r
    val CommitStatus = """/repos/org(\d+)/repo\d+/commits/sha\d+/status""".r
    // the token handed out for installation n is "tokn"
    def tokenId = req.authorization.stripPrefix("token tok").toInt

    (req.method, req.path) match {
      case ("GET", "/app/installations") =>
        Some(Reply(200, JsArray((1 to 6).map(id => installation(id, suspendedIds(id))))))
      case ("POST", TokenPath(id)) =>
        if (suspendedIds(id.toInt) || brokenIds(id.toInt)) Some(Reply(403, Json.obj("message" -> "This installation has been suspended")))
        else Some(Reply(201, Json.obj("token" -> s"tok$id")))
      case ("GET", "/installation/repositories") =>
        Some(Reply(200, Json.obj("repositories" -> Json.arr(Json.obj("name" -> s"repo$tokenId", "owner" -> Json.obj("login" -> s"org$tokenId"), "private" -> false)))))
      case ("GET", Pulls(id)) =>
        val pullRequest = Json.obj(
          "number" -> 1,
          "state" -> "open",
          "head" -> Json.obj("sha" -> s"sha$id"),
          "base" -> Json.obj("repo" -> Json.obj("name" -> s"repo$id", "owner" -> Json.obj("login" -> s"org$id")))
        )
        Some(Reply(200, if (withFailingPr(id.toInt)) Json.arr(pullRequest) else Json.arr()))
      case ("GET", Commits(_)) =>
        Some(Reply(200, Json.arr(Json.obj("author" -> Json.obj("login" -> "signer"), "commit" -> Json.obj("author" -> Json.obj("name" -> "Signer", "email" -> "signer@example.com"))))))
      case ("GET", CommitStatus(_)) =>
        Some(Reply(200, Json.obj("state" -> "failure")))
      case _ => None
    }
  }

  "GitHub.pullRequestsToBeValidated" must {
    "find the failing PRs of the healthy installations and skip the suspended and broken ones" in {
      withFake(scanReplies) { fake =>
        val pullRequests = await(gitHubFor(fake).pullRequestsToBeValidated(signer))
        pullRequests.values.toSet must equal (Set("tok1", "tok4", "tok6"))
        fake.seen must not contain "POST /app/installations/2/access_tokens"
        fake.seen must contain ("POST /app/installations/3/access_tokens")
      }
    }
  }

  "GitHub.pullRequestsToBeValidatedInBatches" must {
    "hand a batch over before the next installations get their tokens" in {
      withFake(scanReplies) { fake =>
        val handled = await(gitHubFor(fake).pullRequestsToBeValidatedInBatches[Int](signer) { found =>
          fake.requests.add(s"HANDLE ${found.size}")
          Future.successful(Seq(found.size))
        })

        // the active installations are 1, 3, 4, 5 and 6, which makes the batches (1, 3, 4, 5) and (6)
        handled.toList must equal (List(2, 1))
        fake.seen.indexOf("HANDLE 2") must be >= 0
        fake.seen.indexOf("HANDLE 2") must be < fake.seen.indexOf("POST /app/installations/6/access_tokens")
      }
    }
    "keep scanning when a batch can't be handled" in {
      withFake(scanReplies) { fake =>
        val handled = await(gitHubFor(fake).pullRequestsToBeValidatedInBatches[Int](signer) { found =>
          if (found.size == 2) Future.failed(new Exception("boom")) else Future.successful(Seq(found.size))
        })

        handled.toList must equal (List(1))
      }
    }
  }

  "Application.status" must {
    def get(app: Application, path: String) = {
      route(app, FakeRequest(GET, path)).get
    }

    "answer 404 when the app is not installed, without looking at anything else" in {
      withFake(_ => None) { fake =>
        val app = appFor(fake)
        running(app) {
          val result = get(app, "/status/org/repo/pull/1")
          status(result) must equal (NOT_FOUND)
          contentAsString(result) must include ("not installed")
          fake.seen must equal (List("GET /repos/org/repo/installation"))
        }
      }
    }
    "reach the pull request when the URL is spelled differently from GitHub's name" in {
      withFake {
        case Req("GET", "/repos/ORG/REPO/installation", _, _) => Some(Reply(200, Json.obj("id" -> 7)))
        case Req("POST", "/app/installations/7/access_tokens", _, _) => Some(Reply(201, Json.obj("token" -> "tok7")))
        // GitHub spells the repo differently from the URL
        case Req("GET", "/repos/ORG/REPO", _, _) => Some(Reply(200, Json.obj("private" -> false, "name" -> "repo", "owner" -> Json.obj("login" -> "org"))))
        case _ => None
      } { fake =>
        val app = appFor(fake)
        running(app) {
          val result = get(app, "/status/ORG/REPO/pull/1")
          // the pull request doesn't exist in the fake, so this is the "Repo Not Found" answer and not a 500
          status(result) must equal (NOT_FOUND)
          contentAsString(result) must equal ("Repo Not Found")
          fake.seen must contain ("GET /repos/ORG/REPO/pulls/1")
          fake.seen must not contain "GET /installation/repositories"
        }
      }
    }
    "ask for a login when the repo is private" in {
      withFake {
        case Req("GET", "/repos/org/repo/installation", _, _) => Some(Reply(200, Json.obj("id" -> 7)))
        case Req("POST", "/app/installations/7/access_tokens", _, _) => Some(Reply(201, Json.obj("token" -> "tok7")))
        case Req("GET", "/repos/org/repo", _, _) => Some(Reply(200, Json.obj("private" -> true)))
        case _ => None
      } { fake =>
        val app = appFor(fake)
        running(app) {
          val result = get(app, "/status/org/repo/pull/1")
          status(result) must equal (SEE_OTHER)
          redirectLocation(result).get must startWith ("https://github.com/login/oauth/authorize")
        }
      }
    }
    "answer 500 when GitHub fails" in {
      withFake(_ => Some(Reply(500, Json.obj("message" -> "boom")))) { fake =>
        val app = appFor(fake)
        running(app) {
          val result = get(app, "/status/org/repo/pull/1")
          status(result) must equal (INTERNAL_SERVER_ERROR)
        }
      }
    }
  }
}
