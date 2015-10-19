package models

import java.util.Date

import jdub.async.{Row, Query, Statement}
import org.joda.time.DateTime


case class ClaSignature(id: Int, contact: Contact, signedOn: Date, claVersion: String)

object ClaSignature {
  def rowToClaSignature(row: Row): ClaSignature = {
    val id = row.as[Int]("cla_id")
    val signedOn = row.as[DateTime]("signed_on__c")
    val claVersion = row.as[String]("cla_version__c")
    val contact = Contact.rowToContact(row)
    ClaSignature(id, contact, signedOn.toDate, claVersion)
  }
}

case class CreateClaSignature(claSignature: ClaSignature) extends Statement {
  override val sql = "INSERT INTO salesforce.cla_signature__c (contact__r__github_id__c, signed_on__c, cla_version__c) VALUES (?, ?, ?)"
  override val values = Seq(claSignature.contact.gitHubId, claSignature.signedOn, claSignature.claVersion)
}

case class GetClaSignatures(gitHubIds: Set[String]) extends Query[Seq[ClaSignature]] {
  override def sql =
    s"""
       |SELECT *, cla_signature__c.id AS cla_id
       |FROM salesforce.cla_signature__c
       |INNER JOIN salesforce.contact ON (salesforce.contact.github_id__c = cla_signature__c.contact__r__github_id__c)
       |WHERE contact__r__github_id__c = ANY(?)
     """.stripMargin
  override val values = Seq(gitHubIds)
  override def reduce(rows: Iterator[Row]): Seq[ClaSignature] = rows.map(ClaSignature.rowToClaSignature).toSeq
}