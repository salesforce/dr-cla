package object utils {

  case class UnauthorizedError(message: String) extends Exception {
    override def getMessage = message
  }

}
