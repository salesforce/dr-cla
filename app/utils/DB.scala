/*
 * Copyright (c) 2018-2026, Salesforce.com
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
    run {
      contacts.insert(
        _.firstName -> lift(contact.firstName),
        _.lastName -> lift(contact.lastName),
        _.email -> lift(contact.email),
        _.gitHubId -> lift(contact.gitHubId)
      ).returning(_.id)
    }.map { newId =>
      contact.copy(id = newId)
    }.recoverWith {
      // Race condition: concurrent requests for the same GitHub user both pass the
      // findContactByGitHubId check and attempt an INSERT. Fall back to the winner's row.
      case e if isDuplicateKey(e) =>
        findContactByGitHubId(contact.gitHubId).map(_.getOrElse(throw e))
    }
  }

  private def isDuplicateKey(e: Throwable): Boolean =
    e.getMessage != null && e.getMessage.contains("duplicate key")

  def createClaSignature(claSignature: ClaSignature): Future[ClaSignature] = {
    run {
      claSignatures.insert(
        _.contactGitHubId -> lift(claSignature.contactGitHubId),
        _.signedOn -> lift(claSignature.signedOn),
        _.claVersion -> lift(claSignature.claVersion)
      ).returning(_.id)
    }.map(newId => claSignature.copy(id = newId))
  }

  def findClaSignaturesByGitHubIds(gitHubIds: Set[GitHub.User]): Future[Set[ClaSignature]] = {
    val queryResult = run {
      claSignatures.filter(claSignature => liftQuery(gitHubIds.map(_.username)).contains(claSignature.contactGitHubId))
    }

    queryResult.map(_.toSet)
  }

}
