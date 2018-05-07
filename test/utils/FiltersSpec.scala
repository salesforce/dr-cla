/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import modules.{Database, DatabaseMock}
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.Mode
import play.api.http.HeaderNames
import play.api.inject._
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc._
import play.api.test.Helpers._
import play.api.test._

class FiltersSpec extends PlaySpec with GuiceOneAppPerTest {

  lazy val appBuilder = new GuiceApplicationBuilder()
    .overrides(bind[Database].to[DatabaseMock])
    .configure("play.modules.disabled" -> Seq("org.flywaydb.play.PlayModule", "modules.DatabaseModule"))
    .in(Mode.Test)

  override def fakeApplication() = appBuilder.build()

  "Filters" must {
    "redirect to https if the request was forwarded and not https" in {
      val Some(result) = route(app, FakeRequest(GET, controllers.routes.Application.signedCla(None).url, Headers(HeaderNames.X_FORWARDED_PROTO -> "http"), AnyContentAsEmpty))
      status(result) mustEqual MOVED_PERMANENTLY
    }
    "not force https for well-known requests" in {
      val Some(result) = route(app, FakeRequest(GET, controllers.routes.Application.wellKnown("test").url))
      status(result) mustEqual NOT_FOUND
    }
    "not force https for non-forwarded requests" in {
      val Some(result) = route(app, FakeRequest(GET, controllers.routes.Application.signedCla(None).url))
      status(result) mustEqual OK
    }
    "keep https for non-well-known requests" in {
      val Some(result) = route(app, FakeRequest(GET, controllers.routes.Application.signedCla(None).url, Headers(HeaderNames.X_FORWARDED_PROTO -> "https"), AnyContentAsEmpty))
      status(result) mustEqual OK
    }
    "return the well known value when the WELL_KNOWN env var is set" in {
      implicit val app = appBuilder.configure("wellknown" -> "foo=bar").build()
      val Some(result) = route(app, FakeRequest(GET, controllers.routes.Application.wellKnown("foo").url))
      status(result) mustEqual OK
      contentAsString(result) mustEqual "bar"
    }
    "not leak well known values" in {
      implicit val app = appBuilder.configure("wellknown" -> "foo=bar").build()
      val Some(result) = route(app, FakeRequest(GET, controllers.routes.Application.wellKnown("").url))
      status(result) mustEqual NOT_FOUND
    }
  }

}
