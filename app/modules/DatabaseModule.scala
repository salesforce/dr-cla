package modules

import java.net.URI
import javax.inject.{Inject, Singleton}

import com.github.mauricio.async.db.pool.{ConnectionPool, PoolConfiguration}
import com.github.mauricio.async.db.postgresql.PostgreSQLConnection
import com.github.mauricio.async.db.postgresql.pool.PostgreSQLConnectionFactory
import com.github.mauricio.async.db.{Connection, QueryResult, SSLConfiguration}
import jdub.async.{RawQuery, Statement}
import org.slf4j.LoggerFactory
import play.api.inject.{ApplicationLifecycle, Binding, Module}
import play.api.{Configuration, Environment}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}


class DatabaseModule extends Module {
  def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] = {
    Seq(
      bind[Database].to[DatabaseImpl]
    )
  }
}

trait Database {
  val pool: ConnectionPool[PostgreSQLConnection]
  def transaction[A](f: (Connection) => Future[A]): Future[A]
  def execute(statement: Statement): Future[Int]
  def query[A](query: RawQuery[A]): Future[A]
  def raw(name: String, sql: String): Future[QueryResult]
}


// based on: https://github.com/KyleU/jdub-async/blob/master/src/main/scala/jdub/async/Database.scala
@Singleton
class DatabaseImpl @Inject()(lifecycle: ApplicationLifecycle, playConfig: Configuration) (implicit ec: ExecutionContext) extends Database {

  val log = LoggerFactory.getLogger(this.getClass)

  // create the pool
  val dbUri = playConfig.getString("db.default.url").map(new URI(_)).getOrElse(throw new IllegalStateException("The db.default.url config must be defined"))
  val userInfoParts = dbUri.getUserInfo.split(":")
  val (username, maybePassword) = if (userInfoParts.isEmpty) {
    ("", None)
  }
  else if (userInfoParts.length == 1) {
    (userInfoParts.head, None)
  }
  else {
    (userInfoParts.head, userInfoParts.lastOption)
  }

  val dbName = dbUri.getPath.stripPrefix("/")
  val maybeDbName = if (dbName.isEmpty) {
    None
  }
  else {
    Some(dbName)
  }

  val maxPoolSize = playConfig.getInt("db.default.maxPoolSize").getOrElse(100)
  val maxIdleMillis = playConfig.getInt("db.default.maxIdleMillis").getOrElse(10)
  val maxQueueSize = playConfig.getInt("db.default.maxQueueSize").getOrElse(1000)

  val sslConfig = SSLConfiguration(playConfig.getString("db.default.sslmode").fold(Map.empty[String, String])(sslmode => Map("sslmode" -> sslmode)))

  val configuration = com.github.mauricio.async.db.Configuration(username, dbUri.getHost, dbUri.getPort, maybePassword, maybeDbName, sslConfig)
  val factory = new PostgreSQLConnectionFactory(configuration)
  val poolConfig = new PoolConfiguration(maxPoolSize, maxIdleMillis, maxQueueSize)
  val pool = new ConnectionPool(factory, poolConfig)

  val healthCheck = pool.sendQuery("select now()")
  healthCheck.onFailure {
    case x => throw new IllegalStateException("Cannot connect to database.", x)
  }
  Await.result(healthCheck.map(r => r.rowsAffected == 1.toLong), 5.seconds)


  private[this] def prependComment(obj: Object, sql: String) = s"/* ${obj.getClass.getSimpleName.replace("$", "")} */ $sql"

  def transaction[A](f: (Connection) => Future[A]): Future[A] = pool.inTransaction(c => f(c))

  def execute(statement: Statement): Future[Int] = {
    val name = statement.getClass.getSimpleName.replaceAllLiterally("$", "")
    log.debug(s"Executing statement [$name] with SQL [${statement.sql}] with values [${statement.values.mkString(", ")}].")
    val ret = pool.sendPreparedStatement(prependComment(statement, statement.sql), statement.values).map(_.rowsAffected.toInt)
    ret.onFailure {
      case x: Throwable => log.error(s"Error [${x.getClass.getSimpleName}] encountered while executing statement [$name].", x)
    }
    ret
  }

  def query[A](query: RawQuery[A]): Future[A] = {
    val name = query.getClass.getSimpleName.replaceAllLiterally("$", "")
    log.debug(s"Executing query [$name] with SQL [${query.sql}] with values [${query.values.mkString(", ")}].")
    val ret = pool.sendPreparedStatement(prependComment(query, query.sql), query.values).map { r =>
      query.handle(r.rows.getOrElse(throw new IllegalStateException()))
    }
    ret.onFailure {
      case x: Throwable => log.error(s"Error [${x.getClass.getSimpleName}] encountered while executing query [$name].", x)
    }
    ret
  }

  def raw(name: String, sql: String): Future[QueryResult] = {
    log.debug(s"Executing raw query [$name] with SQL [$sql].")
    val ret = pool.sendQuery(prependComment(name, sql))
    ret.onFailure {
      case x: Throwable => log.error(s"Error [${x.getClass.getSimpleName}] encountered while executing raw query [$name].", x)
    }
    ret
  }

  lifecycle.addStopHook { () =>
    pool.close.map(_ => Unit)
  }
}
