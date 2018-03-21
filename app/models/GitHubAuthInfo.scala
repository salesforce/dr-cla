/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package models

case class GitHubAuthInfo(encAuthToken: String, userName: String, maybeFullName: Option[String], maybeEmail: Option[String])