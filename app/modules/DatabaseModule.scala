/*
 * Copyright (c) 2017, salesforce.com, inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

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
