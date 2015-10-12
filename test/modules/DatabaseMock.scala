package modules

import com.github.mauricio.async.db.pool.ConnectionPool
import com.github.mauricio.async.db.postgresql.PostgreSQLConnection
import com.github.mauricio.async.db.{Connection, QueryResult}
import jdub.async.{RawQuery, Statement}

import scala.concurrent.Future

class DatabaseMock extends Database {
  override val pool: ConnectionPool[PostgreSQLConnection] = null

  override def raw(name: String, sql: String): Future[QueryResult] = null

  override def execute(statement: Statement): Future[Int] = null

  override def transaction[A](f: (Connection) => Future[A]): Future[A] = null

  override def query[A](query: RawQuery[A]): Future[A] = null
}
