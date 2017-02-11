package models

import modules.Database
import org.scalatestplus.play.{OneAppPerSuite, PlaySpec}
import org.flywaydb.play.PlayInitializer
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._

class ContactSpec extends PlaySpec with OneAppPerSuite {

  val dbUrl = sys.env.get("DATABASE_URL").getOrElse("postgres://salesforcecla:password@localhost:5432/salesforcecla-test")

  val testConfig = Map("db.default.url" -> dbUrl)

  implicit override lazy val app = new GuiceApplicationBuilder().configure(testConfig).build()

  lazy val db = app.injector.instanceOf[Database]
  lazy val playInitializer = app.injector.instanceOf[PlayInitializer]

  await(db.raw("reset db", "drop schema salesforce cascade"))
  await(db.raw("reset db", "drop table schema_version"))

  playInitializer.onStart()

  "Contact" must {
    "be creatable" in {
      val numRows = await(db.execute(CreateContact(Contact(-1, "foo", "bar", "foo@bar.com", "foobar"))))
      numRows mustEqual 1
    }
    "be able to get all" in {
      val contacts = await(db.query(GetContacts))
      contacts.length mustBe > (0)
    }
    "be able to get one that exists by the gitHubId" in {
      val contact = await(db.query(GetContactByGitHubId("foobar")))
      contact mustBe 'defined
    }
    "fail to get one that doesn't exist by a gitHubId" in {
      val contact = await(db.query(GetContactByGitHubId("asdf")))
      contact mustBe None
    }
  }

  "Contact.fullNameToFirstAndLast" must {
    "work with no names" in {
      Contact.fullNameToFirstAndLast("") must equal ("", "")
    }
    "work with one name" in {
      Contact.fullNameToFirstAndLast("Foo") must equal ("", "Foo")
    }
    "work with two names" in {
      Contact.fullNameToFirstAndLast("Foo Bar") must equal ("Foo", "Bar")
    }
    "work with three names" in {
      Contact.fullNameToFirstAndLast("Foo Baz Bar") must equal ("Foo Baz", "Bar")
    }
  }

}
