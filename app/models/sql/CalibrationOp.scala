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
    startTime = rs.timestamp("startTime"),
    endTime = rs.timestamp("endTime"),
    zero_val = rs.doubleOpt("zero_val"),
    span_std = rs.doubleOpt("span_std"),
    span_val = rs.doubleOpt("span_val"),
    zero_success = rs.booleanOpt("zero_success"),
    span_success = rs.booleanOpt("span_success"),
    point3 = rs.doubleOpt("point3"),
    point3_std = rs.doubleOpt("point3_std"),
    point3_success = rs.booleanOpt("point3_success"),
    point4 = rs.doubleOpt("point4"),
    point4_std = rs.doubleOpt("point4_std"),
    point4_success = rs.booleanOpt("point4_success"),
    point5 = rs.doubleOpt("point5"),
    point5_std = rs.doubleOpt("point5_std"),
    point5_success = rs.booleanOpt("point5_success"),
    point6 = rs.doubleOpt("point6"),
    point6_std = rs.doubleOpt("point6_std"),
    point6_success = rs.booleanOpt("point6_success"))


  override def calibrationReport(mt: String, start: Imports.DateTime, end: Imports.DateTime): Seq[Calibration] = {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
         Select *
         From calibration
         Where startTime >= ${start.toDate} and startTime < ${end.toDate} and monitorType = $mt
         Order by startTime
         """.map(mapper).list().apply()
  }

  override def insertFuture(cal: Calibration): Unit = {
    implicit val session: DBSession = AutoSession
    sql"""
      INSERT INTO [dbo].[calibration]
           ([monitorType]
           ,[startTime]
           ,[endTime]
           ,[zero_val]
           ,[span_std]
           ,[span_val]
            ,[zero_success]
            ,[span_success]
            ,[point3]
            ,[point3_std]
            ,[point3_success]
            ,[point4]
            ,[point4_std]
            ,[point4_success]
            ,[point5]
            ,[point5_std]
            ,[point5_success]
            ,[point6]
            ,[point6_std]
            ,[point6_success])
     VALUES
           (${cal.monitorType}
           ,${cal.startTime}
           ,${cal.endTime}
           ,${cal.zero_val}
           ,${cal.span_std}
           ,${cal.span_val}
           ,${cal.zero_success}
           ,${cal.span_success}
           ,${cal.point3}
           ,${cal.point3_std}
           ,${cal.point3_success}
           ,${cal.point4}
           ,${cal.point4_std}
           ,${cal.point4_success}
           ,${cal.point5}
           ,${cal.point5_std}
           ,${cal.point5_success}
           ,${cal.point6}
           ,${cal.point6_std}
           ,${cal.point6_success})
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
              [zero_success] [bit] NULL,
              [span_success] [bit] NULL,
              [point3] [float] NULL,
              [point3_std] [float] NULL,
              [point3_success] [bit] NULL,
              [point4] [float] NULL,
              [point4_std] [float] NULL,
              [point4_success] [bit] NULL,
              [point5] [float] NULL,
              [point5_std] [float] NULL,
              [point5_success] [bit] NULL,
              [point6] [float] NULL,
              [point6_std] [float] NULL,
              [point6_success] [bit] NULL,
          CONSTRAINT [PK_calibration_1] PRIMARY KEY CLUSTERED
          (
	          [monitorType] ASC,
	          [startTime] ASC
          )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON) ON [PRIMARY]
          ) ON [PRIMARY]
           """.execute().apply()
    } else {
      // Check if the table structure is correct
      val columns = sqlServer.getColumnNames(tabName)
      val pointColumns = List("point3", "point4", "point5", "point6")
      // Add columns if not exist
      if (!columns.contains("zero_success")) {
        sql"""
           ALTER TABLE [dbo].[calibration]
           ADD [zero_success] bit;
           ALTER TABLE [dbo].[calibration]
           ADD [span_success] bit;
           """.execute().apply()
      }

      for (col <- pointColumns) {
        if (!columns.contains(col)) {
          val point = SQLSyntax.createUnsafely(col)
          val point_std = SQLSyntax.createUnsafely(s"${col}_std")
          val point_success = SQLSyntax.createUnsafely(s"${col}_success")
          sql"""
           ALTER TABLE [dbo].[calibration]
           ADD [$point] float;
           ALTER TABLE [dbo].[calibration]
           ADD [$point_std] float;
           ALTER TABLE [dbo].[calibration]
           ADD [$point_success] bit;
           """.execute().apply()
        }
      }
    }
  }

  override def getLatestCalibration(mt: String): Future[Option[Calibration]] = Future {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
         Select top 1 *
         From calibration
         Where monitorType = $mt
         Order by startTime desc
         """.map(mapper).single().apply()
  }
}
