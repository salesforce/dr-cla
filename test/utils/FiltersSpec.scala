/*
 * Copyright (c) 2017, salesforce.com, inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package utils

import modules.{Database, DatabaseMock}
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import org.scalatestplus.play.PlaySpec
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
      val Some(result) = route(app, FakeRequest(GET, controllers.routes.Application.signedCla().url, Headers(HeaderNames.X_FORWARDED_PROTO -> "http"), AnyContentAsEmpty))
      status(result) mustEqual MOVED_PERMANENTLY
    }
    "not force https for well-known requests" in {
      val Some(result) = route(app, FakeRequest(GET, controllers.routes.Application.wellKnown("test").url))
      status(result) mustEqual NOT_FOUND
    }
    "not force https for non-forwarded requests" in {
      val Some(result) = route(app, FakeRequest(GET, controllers.routes.Application.signedCla().url))
      status(result) mustEqual OK
    }
    "keep https for non-well-known requests" in {
      val Some(result) = route(app, FakeRequest(GET, controllers.routes.Application.signedCla().url, Headers(HeaderNames.X_FORWARDED_PROTO -> "https"), AnyContentAsEmpty))
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
