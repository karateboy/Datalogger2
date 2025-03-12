package models.sql

import com.github.nscala_time.time.Imports
import com.mongodb.client.result.UpdateResult
import models.{ManualAuditLog, ManualAuditLogDB}
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
            SET [dateTime] = ${log.dataTime}
                ,[mt] = ${log.mt}
                ,[modifiedTime] = ${log.modifiedTime}
                ,[operator] = ${log.operator}
                ,[changedStatus] = ${log.changedStatus}
                ,[reason] = ${log.reason}
                Where [dateTime] = ${log.dataTime} and [mt] = ${log.mt}
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
                (${log.dataTime}
                ,${log.mt}
                ,${log.modifiedTime}
                ,${log.operator}
                ,${log.changedStatus}
                ,${log.reason})
            END
         """.update().apply()
    UpdateResult.acknowledged(ret, ret, null)
  }

  init()

  override def queryLog2(startTime: Imports.DateTime, endTime: Imports.DateTime): Future[Seq[ManualAuditLog]] = Future {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
         Select *
         From [dbo].[auditLog]
         Where [dateTime] >= ${startTime.toDate} and  [dateTime] < ${endTime.toDate}
         """.map(mapper).list().apply()
  }

  private def mapper(rs: WrappedResultSet): ManualAuditLog =
    ManualAuditLog(rs.timestamp("dateTime"),
      mt = rs.string("mt"),
      modifiedTime = rs.timestamp("modifiedTime"),
      operator = rs.string("operator"),
      changedStatus = rs.string("changedStatus"),
      reason = rs.string("reason")
    )

  private def init()(implicit session: DBSession = AutoSession): Unit = {
    if (!sqlServer.getTables().contains(tabName)) {
      sql"""
          CREATE TABLE [dbo].[auditLog](
	          [id] [bigint] IDENTITY(1,1) NOT NULL,
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
  }
}
