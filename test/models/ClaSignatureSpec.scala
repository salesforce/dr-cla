package models

import java.util.{Date, UUID}

import modules.{Database, DatabaseImpl}
import org.scalatestplus.play.{OneAppPerSuite, PlaySpec}
import play.api.Mode
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._

class ClaSignatureSpec extends PlaySpec with OneAppPerSuite {

  val testConfig = Map(
    "db.default.url" -> "postgres://salesforcecla:password@localhost:5432/salesforcecla-test",
    "db.default.migration.auto" -> true,
    "db.default.migration.validateOnMigrate" -> false,
    "db.default.migration.initOnMigrate" -> true
  )

  override lazy val app = new GuiceApplicationBuilder()
    .bindings(bind[Database].to[DatabaseImpl])
    .configure(testConfig)
    .in(Mode.Test)
    .build()

  lazy val db = app.injector.instanceOf[Database]

  "ClaSignature" must {
    "be creatable" in {
      val contact = Contact(UUID.randomUUID().toString, "foo", "bar", "foo@bar.com")
      await(db.execute(CreateContact(contact)))
      val numRows = await(db.execute(CreateClaSignature(ClaSignature(UUID.randomUUID().toString, contact, "foobar", new Date(), "0.0.0"))))
      numRows mustEqual 1
    }
  }

}
