package models

import java.util.Date

import jdub.async.{Row, Query, Statement}
import org.joda.time.LocalDate


case class ClaSignature(id: String, contact: Contact, gitHubId: String, signedOn: Date, claVersion: String)

object ClaSignature {
  def rowToClaSignature(row: Row): ClaSignature = {
    val id = row.as[String]("cla_id")
    val githubId = row.as[String]("github_id")
    val signedOn = row.as[LocalDate]("signed_on")
    val claVersion = row.as[String]("cla_version")
    val contact = Contact.rowToContact(row)
    ClaSignature(id, contact, githubId, signedOn.toDate, claVersion)
  }
}

case class CreateClaSignature(claSignature: ClaSignature) extends Statement {
  override val sql = "INSERT INTO salesforce.cla_signature__c VALUES(?, ?, ?, ?, ?)"
  override val values = Seq(claSignature.id, claSignature.contact.id, claSignature.gitHubId, claSignature.signedOn, claSignature.claVersion)
}

case class GetClaSignatures(gitHubIds: Set[String]) extends Query[Seq[ClaSignature]] {
  override def sql =
    s"""
       |SELECT *, cla_signature__c.id AS cla_id
       |FROM salesforce.cla_signature__c
       |INNER JOIN salesforce.contact ON (contact.id = cla_signature__c.contact)
       |WHERE github_id = ANY(?)
     """.stripMargin
  override val values = Seq(gitHubIds)
  override def reduce(rows: Iterator[Row]): Seq[ClaSignature] = rows.map(ClaSignature.rowToClaSignature).toSeq
}