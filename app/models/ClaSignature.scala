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

import jdub.async.{Query, Row, Statement}
import org.joda.time.LocalDateTime


case class ClaSignature(id: Int, contact: Contact, signedOn: LocalDateTime, claVersion: String)

object ClaSignature {
  def rowToClaSignature(row: Row): ClaSignature = {
    val id = row.as[Int]("cla_id")
    val signedOn = row.as[LocalDateTime]("sf_cla__signed_on__c")
    val claVersion = row.as[String]("sf_cla__cla_version__c")
    val contact = Contact.rowToContact(row)
    ClaSignature(id, contact, signedOn, claVersion)
  }
}

case class CreateClaSignature(claSignature: ClaSignature) extends Statement {
  override val sql = "INSERT INTO salesforce.sf_cla__cla_signature__c (sf_cla__contact__r__sf_cla__github_id__c, sf_cla__signed_on__c, sf_cla__cla_version__c) VALUES (?, ?, ?)"
  override val values = Seq(claSignature.contact.gitHubId, claSignature.signedOn, claSignature.claVersion)
}

case class GetClaSignatures(gitHubIds: Set[String]) extends Query[Set[ClaSignature]] {
  override def sql =
    s"""
       |SELECT *, sf_cla__cla_signature__c.id AS cla_id
       |FROM salesforce.sf_cla__cla_signature__c
       |INNER JOIN salesforce.contact ON (salesforce.contact.sf_cla__github_id__c = sf_cla__cla_signature__c.sf_cla__contact__r__sf_cla__github_id__c)
       |WHERE sf_cla__contact__r__sf_cla__github_id__c = ANY(?)
     """.stripMargin
  override val values = Seq(gitHubIds)
  override def reduce(rows: Iterator[Row]): Set[ClaSignature] = rows.map(ClaSignature.rowToClaSignature).toSet
}
