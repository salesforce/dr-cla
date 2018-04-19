/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package modules

import io.getquill.{PostgresAsyncContext, SnakeCase}

class DatabaseMock extends Database {
  override val ctx = new PostgresAsyncContext(SnakeCase, "ctx")
}
