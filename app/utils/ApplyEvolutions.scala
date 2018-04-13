/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import org.flywaydb.play.PlayInitializer
import play.api.inject.guice.GuiceApplicationBuilder

object ApplyEvolutions extends App {
  val app = new GuiceApplicationBuilder().build()

  val playInitializer = app.injector.instanceOf[PlayInitializer]
  playInitializer.onStart()

  app.stop()
}
