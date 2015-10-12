package models

case class GitHubAuthInfo(encAuthToken: String, userName: String, maybeFullName: Option[String], maybeEmail: Option[String])