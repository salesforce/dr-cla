package controllers

import java.util.{Date, UUID}
import javax.inject.Inject

import models._
import modules.Database
import play.api.Environment
import play.api.libs.Crypto
import play.api.mvc._
import utils.GitHub

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.io.Source


class Application @Inject() (env: Environment, gitHub: GitHub, db: Database) extends Controller {

  val claVersions = Set("0.0")
  val latestClaVersion = claVersions.head

  val gitHubOauthScopes = Seq("user","user:email")

  def githubOauthCallback(code: String) = Action.async { request =>
    gitHub.accessToken(code).flatMap { accessToken =>
      gitHub.userInfo(accessToken).map { userInfo =>
        val username = (userInfo \ "login").as[String]
        val maybeFullName = (userInfo \ "name").asOpt[String]
        val maybeEmail = (userInfo \ "email").asOpt[String]
        val encAccessToken = Crypto.encryptAES(accessToken)
        val gitHubAuthInfo = GitHubAuthInfo(encAccessToken, username, maybeFullName, maybeEmail)
        Ok(views.html.claSign(latestClaVersion, authUrl(request, gitHubOauthScopes), Some(gitHubAuthInfo), latestClaVersion, claText(latestClaVersion)))
      }
    } recover {
      case e: utils.UnauthorizedError => Redirect(routes.Application.signCla).flashing("error" -> e.getMessage)
      case e: Exception => InternalServerError(e.getMessage)
    }
  }

  def signCla = Action { request =>
    Ok(views.html.claSign(claVersions.head, authUrl(request, gitHubOauthScopes), None, latestClaVersion, claText(latestClaVersion)))
  }

  def submitCla = Action.async(parse.urlFormEncoded) { request =>
    val maybeClaSignatureFuture = for {
      encGitHubToken <- request.body.get("encGitHubToken").flatMap(_.headOption)
      claVersion <- claVersions.find(request.body.get("claVersion").flatMap(_.headOption).contains)
      fullName <- request.body.get("fullName").flatMap(_.headOption)
      email <- request.body.get("email").flatMap(_.headOption)
      agreeToCLA <- request.body.get("agreeToCLA").flatMap(_.headOption)
    } yield {
        if (agreeToCLA == "on") {
          val gitHubToken = Crypto.decryptAES(encGitHubToken)
          gitHub.userInfo(gitHubToken).map { userInfo =>
            val username = (userInfo \ "login").as[String]
            val (firstName, lastName) = Contact.fullNameToFirstAndLast(fullName)
            val contact = Contact(UUID.randomUUID().toString, firstName, lastName, email)
            ClaSignature(UUID.randomUUID().toString, contact, username, new Date(), claVersion)
          }
        } else {
          Future.failed(new IllegalStateException("The CLA was not agreed to."))
        }
    }

    maybeClaSignatureFuture.fold {
      Future.successful(BadRequest("A required field was not specified."))
    } { claSignatureFuture =>
      claSignatureFuture.flatMap { claSignature =>
        // todo: transaction?
        for {
          contactsCreated <- db.execute(CreateContact(claSignature.contact))
          if contactsCreated == 1
          claSignaturesCreated <- db.execute(CreateClaSignature(claSignature))
          if claSignaturesCreated == 1
        } yield Redirect(routes.Application.signedCla)
      }
    }

  }

  def signedCla = Action {
    Ok(views.html.claSigned())
  }

  private def authUrl(implicit request: RequestHeader, scopes: Seq[String]): String = {
    s"https://github.com/login/oauth/authorize?client_id=${gitHub.clientId}&redirect_uri=$redirectUri&scope=${scopes.mkString(",")}"
  }

  private def redirectUri(implicit request: RequestHeader): String = {
    routes.Application.githubOauthCallback("").absoluteURL(request.secure).stripSuffix("?code=")
  }

  private def claText(version: String): String = {
    val claPath = s"clas/icla-$version.html"
    val claTextInputStream = env.resourceAsStream(claPath).getOrElse(throw new IllegalStateException(s"Could not locate the CLA: $claPath"))
    val claText = Source.fromInputStream(claTextInputStream).mkString
    claTextInputStream.close()
    claText
  }

}
