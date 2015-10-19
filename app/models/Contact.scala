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
    val gitHubId = row.as[String]("github_id__c")
    Contact(id, firstName, lastName, email, gitHubId)
  }

}

case object GetContacts extends Query[Seq[Contact]] {
  override val sql = "SELECT id, firstname, lastname, email, github_id__c FROM salesforce.Contact"
  override def reduce(rows: Iterator[Row]) = rows.map(Contact.rowToContact).toSeq
}

case class GetContactByGitHubId(gitHubId: String) extends FlatSingleRowQuery[Contact] {
  override val sql = "SELECT id, firstname, lastname, email, github_id__c FROM salesforce.Contact WHERE github_id__c = ?"
  override val values = Seq(gitHubId)
  override def flatMap(row: Row) = Some(Contact.rowToContact(row))
}

case class CreateContact(contact: Contact) extends Statement {
  override val sql = "INSERT INTO salesforce.Contact (firstname, lastname, email, github_id__c) VALUES (?, ?, ?, ?)"
  override val values = Seq(contact.firstName, contact.lastName, contact.email, contact.gitHubId)
}
