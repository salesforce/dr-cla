/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package helpers

import java.net.URL

import javax.inject.Inject
import play.api.{Configuration, Environment, Mode}

import scala.io.Source
import scala.util.Try

class ViewHelpers @Inject()
(configuration: Configuration, environment: Environment) {
  val organizationName = configuration.get[String]("app.organization.name")
  val maybeOrganizationLogoUrl = configuration.getOptional[String]("app.organization.logo-url")
  val maybeOrganizationUrl = configuration.getOptional[String]("app.organization.url")
  val maybeOrganizationClaUrl = configuration.getOptional[String]("app.organization.cla-url")

  val claText: String = {
    maybeOrganizationClaUrl
      .flatMap(claUrl => Try(new URL(claUrl)).toOption)
      .orElse(environment.resource("sample-cla.html"))
      .map { claUrl =>
        val text = Source.fromURL(claUrl)
        text.mkString
      } getOrElse {
        throw new Exception("You must set the ORG_CLA environment variable.")
      }
  }
}
