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

@ImplementedBy(classOf[mongodb.MqttSensorOp])
trait MqttSensorDB {

  def getSensorList(group: String): Future[Seq[Sensor]]

  def getAllSensorList: Future[Seq[Sensor]]

  def getSensorMap: Future[Map[String, Sensor]]

  def upsert(sensor: Sensor): Future[UpdateResult]

  def delete(id: String): Future[DeleteResult]

  def deleteByMonitor(monitor: String): Future[DeleteResult]
}
