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

import ch.qos.logback.classic.filter.ThresholdFilter
import ch.qos.logback.classic.{Level, LoggerContext, PatternLayout}
import ch.qos.logback.classic.net.SMTPAppender
import org.slf4j.LoggerFactory
import play.api.Environment
import play.api.libs.logback.LogbackLoggerConfigurator

class LoggerConfigurator extends LogbackLoggerConfigurator {

  override def configure(env: Environment): Unit = {
    super.configure(env)

    val ctx = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]

    val rootLogger = ctx.getLogger("ROOT")

    val maybeSmtpServer = sys.env.get("POSTMARK_SMTP_SERVER")
    val maybePostmarkApiToken = sys.env.get("POSTMARK_API_TOKEN")
    val maybeErrorEmail = sys.env.get("ERROR_EMAIL")

    (maybeSmtpServer, maybePostmarkApiToken, maybeErrorEmail) match {
      case (Some(smtpServer), Some(postmarkApiToken), Some(errorEmail)) =>
        rootLogger.info("Will email errors to $errorEmail")

        val pl = new PatternLayout()
        pl.setContext(ctx)
        pl.setPattern("%date %-5level %logger{35} - %message%n")
        pl.start()

        val thresholdFilter = new ThresholdFilter()
        thresholdFilter.setLevel(Level.ERROR.toString)

        val smtpAppender = new SMTPAppender()
        smtpAppender.setContext(ctx)
        smtpAppender.setLayout(pl)
        smtpAppender.addFilter(thresholdFilter)
        smtpAppender.setSubject("CLA Error: %logger{20} - %m")
        smtpAppender.setSmtpHost(smtpServer)
        smtpAppender.setUsername(postmarkApiToken)
        smtpAppender.setPassword(postmarkApiToken)
        smtpAppender.addTo(errorEmail)
        smtpAppender.setFrom(errorEmail)
        smtpAppender.setSTARTTLS(true)
        smtpAppender.setSSL(true)
        smtpAppender.start()

        rootLogger.addAppender(smtpAppender)
      case _ =>
        // yay side effects!
    }

  }

}
