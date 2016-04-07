package utils

import modules.{Database, DatabaseMock}
import org.scalatestplus.play.{OneAppPerSuite, PlaySpec}
import play.api.Mode
import play.api.inject._
import play.api.inject.guice.GuiceApplicationBuilder

class CryptoSpec extends PlaySpec with OneAppPerSuite {

  override implicit lazy val app = new GuiceApplicationBuilder()
    .overrides(bind[Database].to[DatabaseMock])
    .configure(
      Map(
        "play.modules.disabled" -> Seq("org.flywaydb.play.PlayModule", "modules.DatabaseModule")
      )
    )
    .in(Mode.Test)
    .build()

  val crypto = new Crypto(app.configuration)

  "encryptAES and decryptAES" must {
    "work" in {
      val encrypted = crypto.encryptAES("hello, world")
      encrypted.length must equal (24)
      crypto.decryptAES(encrypted) must equal ("hello, world")
    }
  }

}