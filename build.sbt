name := """DataLogger2"""

version := "1.3.34"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala, LauncherJarPlugin, JavaAppPackaging, WindowsPlugin)

scalaVersion := "2.11.12"

libraryDependencies ++= Seq(
  ws,
  filters,
  "com.github.tototoshi" %% "scala-csv" % "1.3.5",
  "net.sf.marineapi" % "marineapi" % "0.11.0"
)

// https://mvnrepository.com/artifact/org.mongodb.scala/mongo-scala-driver
libraryDependencies += "org.mongodb.scala" %% "mongo-scala-driver" % "4.2.0"


// https://mvnrepository.com/artifact/com.github.nscala-time/nscala-time
libraryDependencies += "com.github.nscala-time" %% "nscala-time" % "2.28.0"

// https://mvnrepository.com/artifact/org.eclipse.paho/org.eclipse.paho.client.mqttv3
libraryDependencies += "org.eclipse.paho" % "org.eclipse.paho.client.mqttv3" % "1.2.5"

// https://mvnrepository.com/artifact/org.apache.poi/poi-ooxml
libraryDependencies += "org.apache.poi" % "poi-ooxml" % "5.0.0"

// https://mvnrepository.com/artifact/io.github.java-native/jssc
libraryDependencies += "io.github.java-native" % "jssc" % "2.9.4"

routesGenerator := StaticRoutesGenerator

mappings in Universal ++=
(baseDirectory.value / "report_template" * "*" get) map
    (x => x -> ("report_template/" + x.getName))
 	
//libraryDependencies += "com.google.guava" % "guava" % "19.0"
scalacOptions += "-feature"
resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"

routesGenerator := InjectedRoutesGenerator

//WIX setting
// general package information (can be scoped to Windows)
maintainer := "Aragorn Huang <karateboy@sagainfo.com.tw>"
packageSummary := "Datalogger 2"
packageDescription := """Datatlogger 2 Windows MSI."""

// wix build information
wixProductId := "2126D26F-2930-42F8-BBAD-5A06C00455B8"
wixProductUpgradeId := "6AEB12FF-C949-42A8-B979-8C412FFCD1B0"