package models

import java.util.Date

import jdub.async.Statement


case class ClaSignature(id: String, contact: Contact, githubId: String, signedOn: Date, claVersion: String) {

}

case class CreateClaSignature(claSignature: ClaSignature) extends Statement {
  override val sql = "INSERT INTO salesforce.CLA_Signature__c VALUES(?, ?, ?, ?, ?)"
  override val values = Seq(claSignature.id, claSignature.contact.id, claSignature.githubId, claSignature.signedOn, claSignature.claVersion)
}