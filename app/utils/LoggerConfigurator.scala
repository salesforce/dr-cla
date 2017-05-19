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

package utils

import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import ch.qos.logback.classic.filter.ThresholdFilter
import ch.qos.logback.classic.{Level, LoggerContext}
import ch.qos.logback.classic.spi.ILoggingEvent
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
              .withHeaders(HeaderNames.AUTHORIZATION -> s"Token token=$pagerDutyToken")
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
