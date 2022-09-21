package models.sql

import com.github.nscala_time.time
import com.github.nscala_time.time.Imports
import models.{Calibration, CalibrationDB}
import scalikejdbc._

import java.util.Date
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class CalibrationOp @Inject()(sqlServer: SqlServer) extends CalibrationDB {
  private val tabName = "calibration"

  override def calibrationReportFuture(start: Imports.DateTime, end: Imports.DateTime): Future[Seq[Calibration]] =
    Future {
      implicit val session: DBSession = ReadOnlyAutoSession
      sql"""
           Select *
           From calibration
           Where startTime >= ${start.toDate} and startTime < ${end.toDate}
           Order by startTime
           """.map(mapper).list().apply()
    }

  init()

  override def calibrationReportFuture(start: Imports.DateTime): Future[Seq[Calibration]] =
    Future {
      implicit val session: DBSession = ReadOnlyAutoSession
      sql"""
         Select *
         From calibration
         Where startTime >= ${start.toDate}
         Order by startTime
         """.map(mapper).list().apply()
    }

  private def mapper(rs: WrappedResultSet) = Calibration(rs.string("monitorType"),
    rs.jodaDateTime("startTime").toDate,
    rs.jodaDateTime("endTime").toDate,
    rs.doubleOpt("zero_val"),
    rs.doubleOpt("span_std"),
    rs.doubleOpt("span_val"),
    rs.string("monitor"))

  override def insertFuture(cal: Calibration): Unit = {
    implicit val session: DBSession = AutoSession
    sql"""
      INSERT INTO [dbo].[calibration]
           ([monitor]
           ,[monitorType]
           ,[startTime]
           ,[endTime]
           ,[zero_val]
           ,[span_std]
           ,[span_val])
     VALUES
           (${cal.monitor}
           ,${cal.monitorType}
           ,${cal.startTime}
           ,${cal.endTime}
           ,${cal.zero_val}
           ,${cal.span_std}
           ,${cal.span_val})
         """.execute().apply()
  }

  private def init()(implicit session: DBSession = AutoSession): Unit = {
    if (!sqlServer.getTables().contains(tabName)) {
      sql"""
           CREATE TABLE [dbo].[calibration](
              [monitor] [nvarchar](50) NOT NULL,
	            [monitorType] [nvarchar](50) NOT NULL,
	            [startTime] [datetime2](7) NOT NULL,
	            [endTime] [datetime2](7) NOT NULL,
	            [zero_val] [float] NULL,
	            [span_std] [float] NULL,
	            [span_val] [float] NULL,
          CONSTRAINT [PK_calibration_1] PRIMARY KEY CLUSTERED
          (
            [monitor] ASC,
	          [monitorType] ASC,
	          [startTime] ASC
          )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON) ON [PRIMARY]
          ) ON [PRIMARY]
           """.execute().apply()
    }
    sqlServer.addMonitorIfNotExist(tabName)
  }

  override def getLatestMonitorRecordTimeAsync(monitor: String): Future[Option[time.Imports.DateTime]] =
    sqlServer.getLatestMonitorRecordTimeAsync(tabName, monitor, "startTime")

  override def monitorCalibrationReport(monitors: Seq[String], start: Date, end: Date): Future[Seq[Calibration]] = ???
}
