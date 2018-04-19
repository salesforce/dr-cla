import de.heikoseeberger.sbtheader.FileType
import play.twirl.sbt.Import.TwirlKeys

lazy val root = project.in(file(".")).enablePlugins(PlayScala, ForcePlugin, AutomateHeaderPlugin)

name := "dr-cla"

scalaVersion := "2.12.5"

resolvers ++= Seq(Resolver.mavenLocal, Resolver.jcenterRepo)

libraryDependencies ++= Seq(
  guice,
  ws,
  filters,

  "com.fasterxml.jackson.core" % "jackson-databind"                % "2.8.10",

  "com.pauldijou"          %% "jwt-play-json"                      % "0.16.0",

  "org.postgresql"         %  "postgresql"                         % "42.1.4",
  "org.flywaydb"           %% "flyway-play"                        % "4.0.0",

  "io.getquill"            %% "quill-async-postgres"               % "2.4.1",

  "org.webjars"            %% "webjars-play"                       % "2.6.3",
  "org.webjars"            %  "salesforce-lightning-design-system" % "2.4.1",
  "org.webjars"            %  "octicons"                           % "3.1.0",

  "org.scalatestplus.play" %% "scalatestplus-play"                 % "3.1.2" % "test"
)

pipelineStages := Seq(digest, gzip)


// The sbt-force plugin can be used to fetch and deploy metadata

username.in(Force) := sys.env.getOrElse("SALESFORCE_USERNAME", "")

password.in(Force) := sys.env.getOrElse("SALESFORCE_PASSWORD", "")

packagedComponents.in(Force) := Seq("sf_cla")


// license header stuff

organizationName := "salesforce.com, inc."

licenses += "BSD-3-Clause" -> url("https://opensource.org/licenses/BSD-3-Clause")

headerMappings += FileType("html") -> HeaderCommentStyle.twirlStyleBlockComment

headerLicense := Some(
  HeaderLicense.Custom(
    """|Copyright (c) 2018, salesforce.com, inc.
       |All rights reserved.
       |SPDX-License-Identifier: BSD-3-Clause
       |For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
       |""".stripMargin
  )
)

headerSources.in(Compile) ++= sources.in(Compile, TwirlKeys.compileTemplates).value

// classpath resources do not need headers
includeFilter.in(headerResources) := NothingFilter

// license report stuff

licenseConfigurations := Set("runtime")
