package models

import com.github.nscala_time.time.Imports
import org.mongodb.scala.result.UpdateResult
import play.api.libs.json.{Json, OWrites}

import java.util.Date
import scala.concurrent.Future

case class ManualAuditLog(dataTime: Date, mt: String, modifiedTime: Date,
                          operator: String, changedStatus: String, reason: String)

trait ManualAuditLogDB {

  implicit val auditLogWrites: OWrites[ManualAuditLog] = Json.writes[ManualAuditLog]

  def upsertLog(log: ManualAuditLog): Future[UpdateResult]

  def queryLog2(startTime: Imports.DateTime, endTime: Imports.DateTime): Future[Seq[ManualAuditLog]]
}
