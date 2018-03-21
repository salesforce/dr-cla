/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package models


// note: gitHubId is nullable in the DB but we only ever query for contacts with githubids, so we make it non-nullable here
case class Contact(id: Int, firstName: Option[String], lastName: String, email: String, gitHubId: String)

object Contact {

  def fullNameToFirstAndLast(fullName: String): (Option[String], Option[String]) = {
    if (fullName.isEmpty) {
      (None, None)
    }
    else if (!fullName.contains(" ")) {
      (None, Some(fullName))
    }
    else {
      val parts = fullName.split("\\s").reverse
      (Some(parts.tail.reverse.mkString(" ")), Some(parts.head))
    }
  }
}
