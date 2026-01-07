name := """DataLogger2"""
val mainVersion = "2.8.88"
val distVersion = "-yl5"
version := s"$mainVersion$distVersion"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala, LauncherJarPlugin, JavaAppPackaging, WindowsPlugin, BuildInfoPlugin)

scalaVersion := "2.12.20"

libraryDependencies ++= Seq(
  ws,
  filters,
  "com.github.tototoshi" %% "scala-csv" % "1.3.5",
  "net.sf.marineapi" % "marineapi" % "0.11.0"
)

libraryDependencies += guice

// https://mvnrepository.com/artifact/com.typesafe.play/play-json
libraryDependencies += "com.typesafe.play" %% "play-json" % "2.8.2"

// https://mvnrepository.com/artifact/org.apache.commons/commons-lang3
libraryDependencies += "org.apache.commons" % "commons-lang3" % "3.17.0"

// https://mvnrepository.com/artifact/org.mongodb.scala/mongo-scala-driver
libraryDependencies += "org.mongodb.scala" %% "mongo-scala-driver" % "4.2.0"

// https://mvnrepository.com/artifact/com.github.nscala-time/nscala-time
libraryDependencies += "com.github.nscala-time" %% "nscala-time" % "3.0.0"

// https://mvnrepository.com/artifact/org.eclipse.paho/org.eclipse.paho.client.mqttv3
libraryDependencies += "org.eclipse.paho" % "org.eclipse.paho.client.mqttv3" % "1.2.5"

// https://mvnrepository.com/artifact/org.apache.poi/poi-ooxml
libraryDependencies += "org.apache.poi" % "poi-ooxml" % "5.0.0"

// https://mvnrepository.com/artifact/com.microsoft.sqlserver/mssql-jdbc
libraryDependencies += "com.microsoft.sqlserver" % "mssql-jdbc" % "9.4.1.jre8"
// https://mvnrepository.com/artifact/com.microsoft.sqlserver/mssql-jdbc_auth
libraryDependencies += "com.microsoft.sqlserver" % "mssql-jdbc_auth" % "9.4.1.x64"
// https://mvnrepository.com/artifact/com.microsoft.sqlserver/mssql-jdbc_auth
libraryDependencies += "com.microsoft.sqlserver" % "mssql-jdbc_auth" % "9.4.1.x86"

// https://mvnrepository.com/artifact/io.github.java-native/jssc
libraryDependencies += "io.github.java-native" % "jssc" % "2.10.2"

// https://mvnrepository.com/artifact/org.scalikejdbc/scalikejdbc
libraryDependencies += "org.scalikejdbc" %% "scalikejdbc" % "3.4.2"
// https://mvnrepository.com/artifact/org.scalikejdbc/scalikejdbc-config
libraryDependencies += "org.scalikejdbc" %% "scalikejdbc-config" % "3.4.2"
// https://mvnrepository.com/artifact/org.scalikejdbc/scalikejdbc-play-initializer
libraryDependencies += "org.scalikejdbc" %% "scalikejdbc-play-initializer" % "2.6.0"

// https://mvnrepository.com/artifact/commons-io/commons-io
libraryDependencies += "commons-io" % "commons-io" % "2.19.0"

// https://mvnrepository.com/artifact/com.typesafe.play/play-mailer
libraryDependencies += "com.typesafe.play" %% "play-mailer" % "7.0.2"

libraryDependencies += "com.typesafe.play" %% "play-mailer-guice" % "7.0.2"

mappings in Universal ++=
(baseDirectory.value / "report_template" * "*" get) map
    (x => x -> ("report_template/" + x.getName))

mappings in Universal ++=
  (baseDirectory.value / "cdxUpload" * "*" get) map
    (x => x -> ("cdxUpload/" + x.getName))

mappings in Universal ++= Seq((baseDirectory.value / "cleanup.bat", "cleanup.bat"))
//libraryDependencies += "com.google.guava" % "guava" % "19.0"
scalacOptions += "-feature"
resolvers += "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases"

routesGenerator := InjectedRoutesGenerator

//WIX setting
// general package information (can be scoped to Windows)
maintainer := "Aragorn Huang <karateboy@sagainfo.com.tw>"
packageSummary := "Datalogger 2"
packageDescription := """Datalogger 2 Windows MSI."""

// wix build information
wixProductId := "2126D26F-2930-42F8-BBAD-5A06C00455B8"
wixProductUpgradeId := "6AEB12FF-C949-42A8-B979-8C412FFCD1B0"