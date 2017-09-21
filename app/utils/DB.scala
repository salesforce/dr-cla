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

package utils

import javax.inject.Inject

import models.{ClaSignature, Contact}
import modules.Database

import scala.concurrent.{ExecutionContext, Future}

class DB @Inject()(database: Database)(implicit ec: ExecutionContext) {

  import database.ctx._

  private val contacts = quote {
    querySchema[Contact](
      "salesforce.contact",
      _.gitHubId -> "sf_cla__github_id__c",
      _.firstName -> "firstname",
      _.lastName -> "lastname"
    )
  }

  private val claSignatures = quote {
    querySchema[ClaSignature](
      "salesforce.sf_cla__cla_signature__c",
      _.signedOn -> "sf_cla__signed_on__c",
      _.claVersion -> "sf_cla__cla_version__c",
      _.contactGitHubId -> "sf_cla__contact__r__sf_cla__github_id__c"
    )
  }

  def findContactByGitHubId(gitHubId: String): Future[Option[Contact]] = {
    val queryResult = run {
      contacts.filter(_.gitHubId == lift(gitHubId))
    }

    queryResult.map(_.headOption)
  }

  def createContact(contact: Contact): Future[Contact] = {
    val queryResult = run {
      contacts.insert(lift(contact)).returning(_.id)
    }

    queryResult.map(newId => contact.copy(id = newId))
  }

  def createClaSignature(claSignature: ClaSignature): Future[ClaSignature] = {
    val queryResult = run {
      claSignatures.insert(lift(claSignature)).returning(_.id)
    }

    queryResult.map(newId => claSignature.copy(id = newId))
  }

  def findClaSignaturesByGitHubIds(gitHubIds: Set[String]): Future[Set[ClaSignature]] = {
    val queryResult = run {
      claSignatures.filter(claSignature => liftQuery(gitHubIds).contains(claSignature.contactGitHubId))
    }

    queryResult.map(_.toSet)
  }

}
