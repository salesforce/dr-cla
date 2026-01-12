/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import javax.inject._
import play.api.http.HttpErrorHandler
import play.api.mvc._
import play.api.mvc.Results._
import play.api.{Configuration, Environment, Logger, Mode}
import play.api.http.Status._
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ErrorHandler @Inject() (
  env: Environment,
  configuration: Configuration,
  errorView: views.html.error,
  clientErrorView: views.html.clientError
)(implicit ec: ExecutionContext) extends HttpErrorHandler {

  private val logger = Logger(this.getClass)
  private val maybeOrgEmail = configuration.getOptional[String]("app.organization.email")
  private val isDev = env.mode == Mode.Dev

  def onClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] = {
    logger.warn(s"Client error: status=$statusCode path=${request.path} msg=$message")

    val userFriendlyMessage = statusCode match {
      case NOT_FOUND => "The page you're looking for doesn't exist."
      case BAD_REQUEST => "The request was invalid. Please check your input and try again."
      case FORBIDDEN => "You don't have permission to access this resource."
      case UNAUTHORIZED => "You need to be authenticated to access this resource."
      case _ => s"An error occurred (status code: $statusCode)."
    }

    Future.successful(
      Status(statusCode)(
        clientErrorView(userFriendlyMessage, statusCode, message, isDev)
      )
    )
  }

  def onServerError(request: RequestHeader, exception: Throwable): Future[Result] = {
    logger.error(s"Server error at path=${request.path}", exception)

    val userFriendlyMessage = maybeOrgEmail.fold(
      "An unexpected error occurred. Please try again later."
    ) { orgEmail =>
      s"An unexpected error occurred. Please try again later. If the problem persists, please contact: $orgEmail"
    }

    val exceptionMessage = if (isDev) {
      Option(exception.getMessage).filter(_.nonEmpty)
    } else {
      None
    }

    val stackTrace = if (isDev) {
      Some(exception.getStackTrace.mkString("\n"))
    } else {
      None
    }

    Future.successful(
      InternalServerError(
        errorView(userFriendlyMessage, exceptionMessage, stackTrace, isDev)
      )
    )
  }
}
