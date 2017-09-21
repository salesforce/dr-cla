import de.heikoseeberger.sbtheader.FileType
import play.twirl.sbt.Import.TwirlKeys

lazy val root = project.in(file(".")).enablePlugins(PlayScala, ForcePlugin, AutomateHeaderPlugin)

name := "salesforce-cla"

scalaVersion := "2.12.3"

resolvers ++= Seq(Resolver.mavenLocal, Resolver.jcenterRepo)

libraryDependencies ++= Seq(
  guice,
  ws,
  filters,

  "com.pauldijou"          %% "jwt-play-json"                      % "0.14.0",

  "org.postgresql"         %  "postgresql"                         % "42.1.4",
  "org.flywaydb"           %% "flyway-play"                        % "4.0.0",

  "io.getquill"            %% "quill-async-postgres"               % "1.3.0",

  "org.webjars"            %% "webjars-play"                       % "2.6.2",
  "org.webjars"            %  "salesforce-lightning-design-system" % "2.4.1",
  "org.webjars"            %  "octicons"                           % "3.1.0",

  "org.scalatestplus.play" %% "scalatestplus-play"                 % "3.1.1" % "test"
)

pipelineStages := Seq(digest, gzip)


// The sbt-force plugin can be used to fetch and deploy metadata

username.in(Force) := sys.env.getOrElse("SALESFORCE_USERNAME", "")

password.in(Force) := sys.env.getOrElse("SALESFORCE_PASSWORD", "")

packagedComponents.in(Force) := Seq("sf_cla")


// license header stuff

organizationName := "salesforce.com, inc."

startYear := Some(2017)

licenses += "BSD-3-Clause" -> url("https://opensource.org/licenses/BSD-3-Clause")

headerMappings += FileType("html") -> HeaderCommentStyle.TwirlStyleBlockComment

unmanagedSources.in(Compile, headerCreate) ++= sources.in(Compile, TwirlKeys.compileTemplates).value


// license report stuff

licenseConfigurations := Set("runtime")
