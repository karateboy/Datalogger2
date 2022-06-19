package models

import com.google.inject.ImplementedBy
import org.mongodb.scala.result.{DeleteResult, UpdateResult}
import play.api.libs.json.Json

import scala.concurrent.Future
case class Sensor(id:String, topic: String, monitor: String, group:String)
object MqttSensor {
  implicit val write = Json.writes[Sensor]
  implicit val read = Json.reads[Sensor]
}
import scala.concurrent.ExecutionContext.Implicits.global

trait MqttSensorDB {
  def getAllSensorList: Future[Seq[Sensor]]

  def getSensorMap: Future[Map[String, Sensor]] = {
    for (sensorList <- getAllSensorList) yield {
      val pairs =
        for (sensor <- sensorList) yield
          sensor.id -> sensor

      pairs.toMap
    }
  }

  def upsert(sensor: Sensor): Future[UpdateResult]

  def delete(id: String): Future[DeleteResult]

}
