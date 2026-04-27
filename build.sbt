import de.heikoseeberger.sbtheader.FileType
import play.twirl.sbt.Import.TwirlKeys

lazy val root = project.in(file(".")).enablePlugins(PlayScala, ForcePlugin, AutomateHeaderPlugin)

name := "dr-cla"

scalaVersion := "2.13.15"

resolvers ++= Seq(Resolver.mavenLocal, Resolver.jcenterRepo)

libraryDependencies ++= Seq(
  guice,
  ws,
  filters,

  "com.fasterxml.jackson.core" % "jackson-core"                    % "2.17.2",
  "com.fasterxml.jackson.core" % "jackson-annotations"             % "2.17.2",
  "com.fasterxml.jackson.core" % "jackson-databind"                % "2.17.2",
  "org.apache.commons"         % "commons-compress"                % "1.27.1",
  "com.google.guava"           % "guava"                           % "33.3.1-jre",
  "org.bouncycastle"           % "bcprov-jdk18on"                  % "1.79",
  "org.bouncycastle"           % "bcpkix-jdk18on"                  % "1.79",

  "com.github.jwt-scala"       %% "jwt-play-json"                  % "10.0.1",

  "org.postgresql"             %  "postgresql"                     % "42.7.4",
  "org.flywaydb"               %% "flyway-play"                    % "8.0.1",

  "io.getquill"                %% "quill-async-postgres"           % "3.12.0",

  "org.webjars"                %% "webjars-play"                   % "2.9.1",
  "org.webjars"                %  "salesforce-lightning-design-system" % "2.4.1",
  "org.webjars"                %  "octicons"                       % "3.1.0",

  "org.scalatestplus.play"     %% "scalatestplus-play"             % "7.0.1" % "test"
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
    """|Copyright (c) 2018-2026, Salesforce.com
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


// turn off doc stuff

publishArtifact in (Compile, packageDoc) := false

publishArtifact in packageDoc := false

sources in (Compile, doc) := Seq.empty
