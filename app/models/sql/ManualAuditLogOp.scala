package models.sql

import com.github.nscala_time.time.Imports
import com.mongodb.client.result.UpdateResult
import models.{ManualAuditLog, ManualAuditLog2, ManualAuditLogDB}
import scalikejdbc._

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class ManualAuditLogOp @Inject()(sqlServer: SqlServer) extends ManualAuditLogDB {
  private val tabName = "auditLog"

  override def upsertLog(log: ManualAuditLog): Future[UpdateResult] = Future {
    implicit val session: DBSession = AutoSession
    val ret =
      sql"""
          UPDATE [dbo].[auditLog]
            SET [dateTime] = ${log.dataTime.toDate}
                ,[mt] = ${log.mt}
                ,[modifiedTime] = ${log.modifiedTime}
                ,[operator] = ${log.operator}
                ,[changedStatus] = ${log.changedStatus}
                ,[reason] = ${log.reason}
                Where [dateTime] = ${log.dataTime.toDate} and [mt] = ${log.mt}
            IF(@@ROWCOUNT = 0)
            BEGIN
              INSERT INTO [dbo].[auditLog]
              ([dateTime]
                ,[mt]
                ,[modifiedTime]
                ,[operator]
                ,[changedStatus]
                ,[reason])
              VALUES
                (${log.dataTime.toDate}
                ,${log.mt}
                ,${log.modifiedTime.toDate}
                ,${log.operator}
                ,${log.changedStatus}
                ,${log.reason})
            END
         """.update().apply()
    UpdateResult.acknowledged(ret, ret, null)
  }

  init()

  override def queryLog2(startTime: Imports.DateTime, endTime: Imports.DateTime): Future[Seq[ManualAuditLog2]] = Future {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
         Select *
         From [dbo].[auditLog]
         Where [dateTime] >= ${startTime.toDate} and  [dateTime] < ${endTime.toDate}
         """.map(mapper).list().apply()
  }

  private def mapper(rs: WrappedResultSet): ManualAuditLog2 =
    ManualAuditLog2(rs.jodaDateTime("dateTime").getMillis,
      mt = rs.string("mt"),
      modifiedTime = rs.jodaDateTime("modifiedTime").getMillis,
      operator = rs.string("operator"),
      changedStatus = rs.string("changedStatus"),
      reason = rs.string("reason"),
      monitor = rs.string("monitor")
    )

  private def init()(implicit session: DBSession = AutoSession): Unit = {
    if (!sqlServer.getTables().contains(tabName)) {
      sql"""
          CREATE TABLE [dbo].[auditLog](
	          [id] [bigint] IDENTITY(1,1) NOT NULL,
            [monitor] [nvarchar](50) NOT NULL,
	          [dateTime] [datetime2](7) NOT NULL,
	          [mt] [nvarchar](50) NOT NULL,
	          [modifiedTime] [datetime2](7) NOT NULL,
	          [operator] [nvarchar](50) NOT NULL,
	          [changedStatus] [nvarchar](50) NOT NULL,
	          [reason] [nvarchar](50) NOT NULL,
            CONSTRAINT [PK_auditLog] PRIMARY KEY CLUSTERED
            (
	          [id] ASC
            )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON) ON [PRIMARY]
          ) ON [PRIMARY]
           """.execute().apply()
    }
    sqlServer.addMonitorIfNotExist(tabName)
  }
}
