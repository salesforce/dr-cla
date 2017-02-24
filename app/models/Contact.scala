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

import jdub.async.{FlatSingleRowQuery, Query, Row, Statement}

case class Contact(id: Int, firstName: String, lastName: String, email: String, gitHubId: String)

object Contact {

  def fullNameToFirstAndLast(fullName: String): (String, String) = {
    val parts = fullName.split("\\s").reverse
    (parts.tail.reverse.mkString(" "), parts.head)
  }

  def rowToContact(row: Row): Contact = {
    val id = row.as[Int]("id")
    val firstName = row.as[String]("firstname")
    val lastName = row.as[String]("lastname")
    val email = row.as[String]("email")
    val gitHubId = row.as[String]("sf_cla__github_id__c")
    Contact(id, firstName, lastName, email, gitHubId)
  }

}

case object GetContacts extends Query[Seq[Contact]] {
  override val sql = "SELECT id, firstname, lastname, email, sf_cla__github_id__c FROM salesforce.Contact"
  override def reduce(rows: Iterator[Row]) = rows.map(Contact.rowToContact).toSeq
}

case class GetContactByGitHubId(gitHubId: String) extends FlatSingleRowQuery[Contact] {
  override val sql = "SELECT id, firstname, lastname, email, sf_cla__github_id__c FROM salesforce.Contact WHERE sf_cla__github_id__c = ?"
  override val values = Seq(gitHubId)
  override def flatMap(row: Row) = Some(Contact.rowToContact(row))
}

case class CreateContact(contact: Contact) extends Statement {
  override val sql = "INSERT INTO salesforce.Contact (firstname, lastname, email, sf_cla__github_id__c) VALUES (?, ?, ?, ?)"
  override val values = Seq(contact.firstName, contact.lastName, contact.email, contact.gitHubId)
}
