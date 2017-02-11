package utils

import modules.{Database, DatabaseMock}
import org.scalatestplus.play.{OneAppPerSuite, PlaySpec}
import play.api.Mode
import play.api.http.HeaderNames
import play.api.inject._
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc._
import play.api.test.Helpers._
import play.api.test._

class FiltersSpec extends PlaySpec with OneAppPerSuite {

  override implicit lazy val app = new GuiceApplicationBuilder()
    .overrides(bind[Database].to[DatabaseMock])
    .configure(
      Map(
        "play.modules.disabled" -> Seq("org.flywaydb.play.PlayModule", "modules.DatabaseModule")
      )
    )
    .in(Mode.Test)
    .build()

  "Filters" must {
    "redirect to https if the request was forwarded and not https" in {
      val Some(result) = route(app, FakeRequest(GET, controllers.routes.Application.signedCla().url, Headers(HeaderNames.X_FORWARDED_PROTO -> "http"), AnyContentAsEmpty))
      status(result) mustEqual MOVED_PERMANENTLY
    }
    "not force https for well-known requests" in {
      val Some(result) = route(app, FakeRequest(GET, controllers.routes.Application.wellKnown("").url))
      status(result) mustEqual OK
    }
    "not force https for non-forwarded requests" in {
      val Some(result) = route(app, FakeRequest(GET, controllers.routes.Application.signedCla().url))
      status(result) mustEqual OK
    }
  }

}
