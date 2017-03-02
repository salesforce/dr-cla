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

package models

import modules.Database
import org.scalatestplus.play.{OneAppPerSuite, PlaySpec}
import org.flywaydb.play.PlayInitializer
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._

class ContactSpec extends PlaySpec with OneAppPerSuite {

  val dbUrl = sys.env.getOrElse("DATABASE_URL", "postgres://salesforcecla:password@localhost:5432/salesforcecla-test")

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
