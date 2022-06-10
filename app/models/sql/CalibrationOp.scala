package models.sql

import com.github.nscala_time.time.Imports
import models.{Calibration, CalibrationDB}
import scalikejdbc._

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class CalibrationOp @Inject()(sqlServer: SqlServer) extends CalibrationDB {
  private val tabName = "calibration"

  override def calibrationReportFuture(start: Imports.DateTime, end: Imports.DateTime): Future[Seq[Calibration]] =
    Future {
      calibrationReport(start, end)
    }

  init()

  override def calibrationReport(start: Imports.DateTime, end: Imports.DateTime): Seq[Calibration] = {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
         Select *
         From calibration
         Where startTime >= ${start.toDate} and startTime < ${end.toDate}
         Order by startTime
         """.map(mapper).list().apply()
  }

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
    rs.jodaDateTime("startTime"),
    rs.jodaDateTime("endTime"),
    rs.doubleOpt("zero_val"),
    rs.doubleOpt("span_std"),
    rs.doubleOpt("span_val"))

  override def calibrationReport(mt: String, start: Imports.DateTime, end: Imports.DateTime): Seq[Calibration] = {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
         Select *
         From calibration
         Where startTime >= ${start.toDate} and startTime < ${end.toDate} and monitorType = $mt
         Order by startTime
         """.map(mapper).list().apply()
  }

  override def insert(cal: Calibration): Unit = {
    implicit val session: DBSession = AutoSession
    sql"""
      INSERT INTO [dbo].[calibration]
           ([monitorType]
           ,[startTime]
           ,[endTime]
           ,[zero_val]
           ,[span_std]
           ,[span_val])
     VALUES
           (${cal.monitorType}
           ,${cal.startTime.toDate}
           ,${cal.endTime.toDate}
           ,${cal.zero_val}
           ,${cal.span_std}
           ,${cal.span_val})
         """.execute().apply()
  }

  private def init()(implicit session: DBSession = AutoSession): Unit = {
    if (!sqlServer.getTables().contains(tabName)) {
      sql"""
           CREATE TABLE [dbo].[calibration](
	            [monitorType] [nvarchar](50) NOT NULL,
	            [startTime] [datetime2](7) NOT NULL,
	            [endTime] [datetime2](7) NOT NULL,
	            [zero_val] [float] NULL,
	            [span_std] [float] NULL,
	            [span_val] [float] NULL,
          CONSTRAINT [PK_calibration_1] PRIMARY KEY CLUSTERED
          (
	          [monitorType] ASC,
	          [startTime] ASC
          )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON) ON [PRIMARY]
          ) ON [PRIMARY]
           """.execute().apply()
    }
  }
}
