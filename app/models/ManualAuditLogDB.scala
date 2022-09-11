package models

import com.github.nscala_time.time.Imports
import com.github.nscala_time.time.Imports.DateTime
import com.google.inject.ImplementedBy
import org.mongodb.scala.result.UpdateResult
import play.api.libs.json.Json

import scala.concurrent.Future

case class ManualAuditLog(dataTime: DateTime, mt: String, modifiedTime: DateTime,
                          operator: String, changedStatus: String, reason: String)

case class ManualAuditLog2(dataTime: Long, mt: String, modifiedTime: Long,
                           operator: String, changedStatus: String, reason: String, monitor:String)

trait ManualAuditLogDB {

  implicit val writer = Json.writes[ManualAuditLog]

  def upsertLog(log: ManualAuditLog): Future[UpdateResult]

  def queryLog2(startTime: Imports.DateTime, endTime: Imports.DateTime): Future[Seq[ManualAuditLog2]]
}
