/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import modules.{Database, DatabaseMock}
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import org.scalatestplus.play.PlaySpec
import play.api.Mode
import play.api.inject._
import play.api.inject.guice.GuiceApplicationBuilder

class CryptoSpec extends PlaySpec with GuiceOneAppPerTest {

  override implicit def fakeApplication() = new GuiceApplicationBuilder()
    .overrides(bind[Database].to[DatabaseMock])
    .configure(
      Map(
        "play.modules.disabled" -> Seq("org.flywaydb.play.PlayModule", "modules.DatabaseModule")
      )
    )
    .in(Mode.Test)
    .build()

  lazy val crypto = new Crypto(app.configuration)

  "encryptAES and decryptAES" must {
    "work" in {
      val encrypted = crypto.encryptAES("hello, world")
      encrypted.length must equal (24)
      crypto.decryptAES(encrypted) must equal ("hello, world")
    }
  }

}
