/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package controllers

import modules.{Database, DatabaseMock}
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.Mode
import play.api.inject._
import play.api.inject.guice.GuiceApplicationBuilder

import scala.xml.Comment

class ApplicationSpec extends PlaySpec with GuiceOneAppPerTest {

  override implicit def fakeApplication() = new GuiceApplicationBuilder()
    .overrides(bind[Database].to[DatabaseMock])
    .configure(
      Map(
        "play.modules.disabled" -> Seq("org.flywaydb.play.PlayModule", "modules.DatabaseModule")
      )
    )
    .in(Mode.Test)
    .build()

  lazy val applicationController = app.injector.instanceOf[Application]

  "svgNode" must {
    "work" in {
      val svg = applicationController.svgSymbol("custom-sprite/svg/symbols.svg", "custom16")
      svg.attribute("d") mustBe 'defined
    }
    "produce a comment when the file can't be found" in {
      val svg = applicationController.svgSymbol("asdfasdf", "custom16")
      svg mustBe a [Comment]
    }
    "produce a comment when the symbol can't be found" in {
      val svg = applicationController.svgSymbol("custom-sprite/svg/symbols.svg", "asdf")
      svg mustBe a [Comment]
    }
  }

}
