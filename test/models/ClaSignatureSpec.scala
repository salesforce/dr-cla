package models

import modules.Database
import org.joda.time.LocalDateTime
import org.scalatestplus.play.{OneAppPerSuite, PlaySpec}
import org.flywaydb.play.PlayInitializer
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._

class ClaSignatureSpec extends PlaySpec with OneAppPerSuite {

  val testConfig = Map("db.default.url" -> "postgres://salesforcecla:password@localhost:5432/salesforcecla-test")

  implicit override lazy val app = new GuiceApplicationBuilder().configure(testConfig).build()

  lazy val db = app.injector.instanceOf[Database]
  lazy val playInitializer = app.injector.instanceOf[PlayInitializer]

  await(db.raw("reset db", "drop schema salesforce cascade"))
  await(db.raw("reset db", "drop table schema_version"))

  playInitializer.onStart()

  "ClaSignature" must {
    "be creatable" in {
      val contact = Contact(-1, "foo", "bar", "foo@bar.com", "foobar")
      await(db.execute(CreateContact(contact)))
      val numRows = await(db.execute(CreateClaSignature(ClaSignature(-1, contact, new LocalDateTime(), "0.0.0"))))
      numRows mustEqual 1
    }
    "be queryable with one github id" in {
      val claSignatures = await(db.query(GetClaSignatures(Set("foobar"))))
      claSignatures.length mustEqual 1
      claSignatures.head.contact.gitHubId mustEqual "foobar"
    }
    "be queryable with a set of github ids" in {
      val claSignatures = await(db.query(GetClaSignatures(Set("foobar", "jondoe"))))
      claSignatures.length mustEqual 1
      claSignatures.head.contact.gitHubId mustEqual "foobar"
    }
  }

}
