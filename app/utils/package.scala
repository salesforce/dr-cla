/*
 * Copyright (c) 2018-2026, Salesforce.com
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package object utils {

  case class UnauthorizedError(message: String) extends Exception {
    override def getMessage = message
  }

}
