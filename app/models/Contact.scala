package models

import jdub.async.{Query, Row, Statement}

case class Contact(id: String, firstName: String, lastName: String, email:String)

object Contact {

  def fullNameToFirstAndLast(fullName: String): (String, String) = {
    val parts = fullName.split("\\s").reverse
    (parts.tail.reverse.mkString(" "), parts.head)
  }

}

case object GetContacts extends Query[Seq[Contact]] {
  override val sql = "SELECT id, firstname, lastname, email FROM salesforce.Contact"
  override val values = Nil

  override def reduce(rows: Iterator[Row]) = rows.map { row =>
    val id = row.as[String]("id")
    val firstName = row.as[String]("firstname")
    val lastName = row.as[String]("lastname")
    val email = row.as[String]("email")
    Contact(id, firstName, lastName, email)
  }.toSeq
}

case class CreateContact(contact: Contact) extends Statement {
  override val sql = "INSERT INTO salesforce.Contact VALUES(?, ?, ?, ?)"
  override val values = Seq(contact.id, contact.firstName, contact.lastName, contact.email)
}
