package models

import org.mongodb.scala.result.InsertOneResult

import java.util.Date
import scala.concurrent.Future

case class AisData(monitor: String, time: Date, json: String)

trait AisDB {
  def getAisData(monitor: String, start: Date, end: Date): Future[Seq[AisData]]

  def getLatestData(monitor: String): Future[Option[AisData]]

  def insertAisData(aisData: AisData): Future[InsertOneResult]

}