name := "salesforce-cla"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.8"

resolvers += Resolver.mavenLocal

libraryDependencies ++= Seq(
  ws,
  filters,

  "org.postgresql"         %  "postgresql"                         % "9.4-1203-jdbc42",
  "org.flywaydb"           %% "flyway-play"                        % "2.2.0",

  "com.github.mauricio"    %% "postgresql-async"                   % "0.2.19",
  "com.kyleu"              %% "jdub-async"                         % "1.0",

  "org.webjars"            %% "webjars-play"                       % "2.5.0",
  "org.webjars"            %  "salesforce-lightning-design-system" % "0.10.1",
  "org.webjars"            %  "octicons"                           % "3.1.0",
  "org.webjars.bower"      %  "signature_pad"                      % "1.5.1",

  "org.scalatestplus.play" %% "scalatestplus-play"                 % "1.5.1" % "test"
)

pipelineStages := Seq(digest, gzip)
