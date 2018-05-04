/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import java.time.LocalDateTime

import models.{ClaSignature, Contact}
import modules.Database
import org.flywaydb.play.PlayInitializer
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try


class DBSpec extends PlaySpec with GuiceOneAppPerSuite {

  val dbUrl = sys.env.getOrElse("DATABASE_URL", "postgres://salesforcecla:password@localhost:5432/salesforcecla-test")

  val testConfig = Map("db.default.url" -> dbUrl)

  implicit override def fakeApplication() = new GuiceApplicationBuilder().configure(testConfig).build()

  lazy val database = app.injector.instanceOf[Database]
  lazy val db = app.injector.instanceOf[DB]
  lazy val playIntializer = app.injector.instanceOf[PlayInitializer]

  Try(await(database.ctx.executeQuery("drop schema salesforce cascade")))
  Try(await(database.ctx.executeQuery("drop table schema_version")))

  playIntializer.onStart()

  "Contact" must {
    "be creatable" in {
      val contact = await(db.createContact(Contact(-1, Some("foo"), "bar", "foo@bar.com", "foobar")))
      contact.id must not equal -1
    }
    "be creatable with null firstname" in {
      val contact = await(db.createContact(Contact(-1, None, "blah", "blah@blah.com", "blah")))
      contact.id must not equal -1
    }
    "be able to get one that exists by the gitHubId" in {
      val contact = await(db.findContactByGitHubId("foobar"))
      contact mustBe 'defined
    }
    "fail to get one that doesn't exist by a gitHubId" in {
      val contact = await(db.findContactByGitHubId("asdf"))
      contact mustBe None
    }
    "work with null firstname" in {
      val contact = await(db.findContactByGitHubId("blah"))
      contact mustBe 'defined
      contact.get.firstName mustBe empty
    }
  }

  "ClaSignature" must {
    "be creatable" in {
      val contact = Contact(-1, Some("foo"), "bar", "foo@bar.com", "foobar")
      val claSignature = await(db.createClaSignature(ClaSignature(-1, contact.gitHubId, LocalDateTime.now(), "0.0.0")))
      claSignature.id must not equal -1
    }
    "be queryable with one github id" in {
      val claSignatures = await(db.findClaSignaturesByGitHubIds(Set(GitHub.User("foobar"))))
      claSignatures.size mustEqual 1
      claSignatures.head.contactGitHubId mustEqual "foobar"
    }
    "be queryable with a set of github ids" in {
      val claSignatures = await(db.findClaSignaturesByGitHubIds(Set(GitHub.User("foobar"), GitHub.User("jondoe"))))
      claSignatures.size mustEqual 1
      claSignatures.head.contactGitHubId mustEqual "foobar"
    }
  }

  "Contact.fullNameToFirstAndLast" must {
    "work with no names" in {
      Contact.fullNameToFirstAndLast("") must equal (None, None)
    }
    "work with one name" in {
      Contact.fullNameToFirstAndLast("Foo") must equal (None, Some("Foo"))
    }
    "work with two names" in {
      Contact.fullNameToFirstAndLast("Foo Bar") must equal (Some("Foo"), Some("Bar"))
    }
    "work with three names" in {
      Contact.fullNameToFirstAndLast("Foo Baz Bar") must equal (Some("Foo Baz"), Some("Bar"))
    }
  }

}
