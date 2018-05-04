/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package helpers

import org.scalatest.Matchers._
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.Mode
import play.api.inject.guice.GuiceApplicationBuilder

class ViewHelpersSpec extends PlaySpec with GuiceOneAppPerTest {
  val testOrgName = "Test Org Name"
  val testOrgUrl = "http://orgurl.org"
  val testOrgLogoUrl = "image.jpg"

  override implicit def fakeApplication() = new GuiceApplicationBuilder()
    .configure(
      Map(
        "app.organization.name" -> testOrgName,
        "app.organization.url" -> testOrgUrl,
        "app.organization.logo-url"-> testOrgLogoUrl
      )
    )
    .in(Mode.Test)
    .build()

  def viewHelper = app.injector.instanceOf[ViewHelpers]

  "ViewHelper" must {
    "give a valid organization name" in {
      val orgName = viewHelper.organizationName
      orgName mustBe a [String]
      orgName mustEqual testOrgName
    }
    "give a valid organization URL" in {
      val orgUrl = viewHelper.maybeOrganizationUrl
      orgUrl shouldBe defined
      orgUrl should contain (testOrgUrl)
    }
    "give a valid organization logo URL" in {
      val orgLogoUrl = viewHelper.maybeOrganizationLogoUrl
      orgLogoUrl shouldBe defined
      orgLogoUrl should contain (testOrgLogoUrl)
    }
    // todo: test loading the sample CLA in dev mode
  }
}
