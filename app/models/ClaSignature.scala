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
