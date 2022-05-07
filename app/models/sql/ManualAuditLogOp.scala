package models.sql

import com.github.nscala_time.time.Imports
import models.{ManualAuditLog, ManualAuditLog2, ManualAuditLogDB}
import org.mongodb.scala.result.UpdateResult

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

@Singleton
class ManualAuditLogOp @Inject()() extends ManualAuditLogDB {
  override def upsertLog(log: ManualAuditLog): Future[UpdateResult] = ???

  override def queryLog(startTime: Imports.DateTime, endTime: Imports.DateTime): Future[Seq[ManualAuditLog]] = ???

  override def queryLog2(startTime: Imports.DateTime, endTime: Imports.DateTime): Future[Seq[ManualAuditLog2]] = ???
}
