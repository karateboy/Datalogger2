package models.sql

import models.{MqttSensorDB, Sensor}
import org.mongodb.scala.result.{DeleteResult, UpdateResult}

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
@Singleton
class MqttSensorOp @Inject()() extends MqttSensorDB{
  override def getSensorList(group: String): Future[Seq[Sensor]] = ???

  override def getAllSensorList: Future[Seq[Sensor]] = ???

  override def getSensorMap: Future[Map[String, Sensor]] = ???

  override def upsert(sensor: Sensor): Future[UpdateResult] = ???

  override def delete(id: String): Future[DeleteResult] = ???

  override def deleteByMonitor(monitor: String): Future[DeleteResult] = ???
}
