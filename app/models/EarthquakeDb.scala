package models

import play.api.{Configuration, Logger}

import java.nio.file.{Files, Paths}
import java.time.{LocalDateTime, ZoneOffset}
import java.time.format.DateTimeFormatter
import javax.inject.{Inject, Singleton}
import scala.collection.JavaConverters.asScalaBufferConverter

case class EarthQuakeData(dateTime:Long, lat:Double, lon:Double, magnitude:Double, depth:Double)
@Singleton
class EarthquakeDb @Inject()(configuration: Configuration) {
  val rootPath = configuration.getString("earthquakeDb.root").get
  Logger.info(s"Earthquake DB root = $rootPath")


  def readData(): Map[LocalDateTime, EarthQuakeData] ={
    val lines =
      Files.readAllLines(Paths.get(rootPath, "EQ_REPORT/EQ.txt")).asScala

    import collection.mutable.Map
    val dataMap = Map.empty[LocalDateTime, EarthQuakeData]
    def handleEntry(line:String): Unit ={
      try{
        val dateTime = LocalDateTime.parse(line.take(20).trim, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val tokens = line.drop(20).trim.split("\\s+")
        val lat = tokens(1).trim.toDouble
        val lon = tokens(0).trim.toDouble
        val magnitude = tokens(2).trim.toDouble
        val depth = tokens(3).trim.toDouble
        dataMap.update(dateTime, EarthQuakeData(dateTime.toEpochSecond(ZoneOffset.ofHours(8))*1000, lat, lon, magnitude, depth))
      }catch{
        case ex:Throwable=>
      }
    }
    lines.foreach(handleEntry)
    dataMap.toMap
  }
  val dataMap: Map[LocalDateTime, EarthQuakeData] = readData()
  Logger.info(s"Total ${dataMap.size} entry in dataMap")
}
