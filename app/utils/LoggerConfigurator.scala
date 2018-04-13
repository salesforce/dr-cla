/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import ch.qos.logback.classic.filter.ThresholdFilter
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, LoggerContext}
import ch.qos.logback.core.AppenderBase
import org.slf4j.LoggerFactory
import play.api.Environment
import play.api.http.HeaderNames
import play.api.libs.json.{JsString, Json}
import play.api.libs.logback.LogbackLoggerConfigurator
import play.api.libs.ws.ahc.AhcWSClient


class LoggerConfigurator extends LogbackLoggerConfigurator {

  implicit lazy val actorSystem = ActorSystem()
  implicit lazy val materializer = ActorMaterializer()
  lazy val wsClient = AhcWSClient()
  var maybePagerDutyAppender: Option[AppenderBase[_]] = None

  override def configure(env: Environment): Unit = {
    super.configure(env)

    val maybePagerDutyToken = sys.env.get("PAGERDUTY_TOKEN")
    val maybePagerDutyIntegrationKey = sys.env.get("PAGERDUTY_INTEGRATION_KEY")

    (maybePagerDutyToken, maybePagerDutyIntegrationKey) match {
      case (Some(pagerDutyToken), Some(pagerDutyIntegrationKey)) =>
        val ctx = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
        val rootLogger = ctx.getLogger("ROOT")
        rootLogger.info("Errors will be sent to PagerDuty")

        val thresholdFilter = new ThresholdFilter()
        thresholdFilter.setLevel(Level.ERROR.levelStr)
        thresholdFilter.setContext(ctx)
        thresholdFilter.start()

        val pagerDutyAppender = new AppenderBase[ILoggingEvent] {

          override def append(eventObject: ILoggingEvent): Unit = {

            val json = Json.obj(
              "routing_key" -> pagerDutyIntegrationKey,
              "event_action" -> "trigger",
              "dedup_key" -> ("salesforce-cla-" + eventObject.getTimeStamp),
              "payload" -> Json.obj(
                "summary" -> eventObject.getFormattedMessage,
                "source" -> JsString(sys.env.getOrElse("HEROKU_DNS_APP_NAME", "salesforce-cla")),
                "severity" -> "error",
                "timestamp" -> Instant.ofEpochMilli(eventObject.getTimeStamp),
                "custom_details" -> Json.obj(
                  "logger-name" -> eventObject.getLoggerName,
                  "thread-name" -> eventObject.getThreadName,
                  "caller-data" -> eventObject.getCallerData.mkString("\n")
                )
              )
            )

            wsClient
              .url("https://events.pagerduty.com/v2/enqueue")
              .withHttpHeaders(HeaderNames.AUTHORIZATION -> s"Token token=$pagerDutyToken")
              .post(json)
          }
        }

        pagerDutyAppender.setContext(ctx)
        pagerDutyAppender.addFilter(thresholdFilter)
        pagerDutyAppender.start()

        rootLogger.addAppender(pagerDutyAppender)

        maybePagerDutyAppender = Some(pagerDutyAppender)
      case _ =>
        // yay side effects
    }
  }

  override def shutdown(): Unit = {
    maybePagerDutyAppender.foreach(_.stop())
    wsClient.close()
    actorSystem.terminate()
  }

}
