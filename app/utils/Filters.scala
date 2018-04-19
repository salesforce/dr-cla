/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import javax.inject.Inject

import akka.stream.Materializer
import play.api.http.{HeaderNames, HttpFilters}
import play.api.mvc._
import play.filters.gzip.GzipFilter

import scala.concurrent.{ExecutionContext, Future}

class OnlyHttpsFilter @Inject() (implicit val mat: Materializer, ec: ExecutionContext) extends Filter {
  def apply(nextFilter: (RequestHeader) => Future[Result])(requestHeader: RequestHeader): Future[Result] = {
    nextFilter(requestHeader).map { result =>
      val isWellKnown = requestHeader.path.startsWith(controllers.routes.Application.wellKnown("").url)
      val isForwardedAndInsecure = requestHeader.headers.get(HeaderNames.X_FORWARDED_PROTO).exists(_ != "https")

      if (isWellKnown || !isForwardedAndInsecure) {
        result
      }
      else {
        Results.MovedPermanently("https://" + requestHeader.host + requestHeader.uri)
      }
    }
  }
}

class Filters @Inject() (gzip: GzipFilter, onlyHttpsFilter: OnlyHttpsFilter) extends HttpFilters {
  val filters = Seq(gzip, onlyHttpsFilter)
}
