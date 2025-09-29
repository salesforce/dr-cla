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
import play.api.Logger
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class IgnoreAllExceptionsHandler @Inject() (implicit ec: ExecutionContext) extends HttpErrorHandler {
  private val logger = Logger(this.getClass)

  def onClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] = {
    logger.warn(s"Swallowing client error: status=$statusCode path=${request.path} msg=$message")
    Future.successful(Results.Ok)
  }

  def onServerError(request: RequestHeader, exception: Throwable): Future[Result] = {
    logger.error(s"Swallowing server error at path=${request.path}", exception)
    Future.successful(Results.Ok)
  }
}



