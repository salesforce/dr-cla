package models

import java.util.{Date, UUID}

import modules.{Database, DatabaseImpl}
import org.flywaydb.play.PlayInitializer
import org.scalatestplus.play.{OneAppPerSuite, PlaySpec}
import play.api.{Application, GlobalSettings, Mode}
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.core.WebCommands

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

  // clear out the db
  await(db.raw("start from scratch", "drop table schema_version"))

  val contactId = UUID.randomUUID().toString
  val claSignatureId = UUID.randomUUID().toString

  "ClaSignature" must {
    "be creatable" in {
      val contact = Contact(contactId, "foo", "bar", "foo@bar.com")
      await(db.execute(CreateContact(contact)))
      val numRows = await(db.execute(CreateClaSignature(ClaSignature(claSignatureId, contact, "foobar", new Date(), "0.0.0"))))
      numRows mustEqual 1
    }
    "be queryable with one github id" in {
      val claSignatures = await(db.query(GetClaSignatures(Set("foobar"))))
      claSignatures.length mustEqual 1
      claSignatures.head.id mustEqual claSignatureId
      claSignatures.head.contact.id mustEqual contactId
    }
    "be queryable with a set of github ids" in {
      val claSignatures = await(db.query(GetClaSignatures(Set("foobar", "jondoe"))))
      claSignatures.length mustEqual 1
      claSignatures.head.id mustEqual claSignatureId
      claSignatures.head.contact.id mustEqual contactId
    }
  }

}
