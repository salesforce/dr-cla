/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
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

  def findClaSignaturesByGitHubIds(gitHubIds: Set[GitHub.GitHubUser]): Future[Set[ClaSignature]] = {
    val queryResult = run {
      claSignatures.filter(claSignature => liftQuery(gitHubIds.map(_.username)).contains(claSignature.contactGitHubId))
    }

    queryResult.map(_.toSet)
  }

}
