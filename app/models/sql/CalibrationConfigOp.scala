package models.sql

import models.{CalibrationConfig, CalibrationConfigDB, PointCalibrationConfig}
import play.api.libs.json.Json
import scalikejdbc._

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import java.time.LocalTime

@Singleton
class CalibrationConfigOp @Inject()(sqlServer: SqlServer) extends CalibrationConfigDB {
  private val tabName = "calibrationConfig"

  private def mapper(rs: WrappedResultSet) = CalibrationConfig(
    _id = rs.string("id"),
    instrumentIds = rs.string("instrumentIds").split(",").toSeq,
    calibrationTime = rs.jodaLocalTimeOpt("calibrationTime").map(t=>t.toString("HH:mm:ss")),
    pointConfigs = Json.parse(rs.string("pointConfigs")).as[Seq[PointCalibrationConfig]]
  )

  private def init()(implicit session: DBSession = AutoSession): Unit = {
    if (!sqlServer.getTables().contains(tabName)) {
      sql"""
           CREATE TABLE [dbo].[calibrationConfig](
	          [id] [nvarchar](50) NOT NULL,
	          [instrumentIds] [nvarchar](128) NOT NULL,
	          [calibrationTime] [time](7) NULL,
	          [pointConfigs] [nvarchar](max) NOT NULL,
          CONSTRAINT [PK_calibrationConfigs] PRIMARY KEY CLUSTERED
          (
	          [id] ASC
          )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON) ON [PRIMARY]
          ) ON [PRIMARY] TEXTIMAGE_ON [PRIMARY]
           """.execute().apply()
    }
  }

  init()

  override def upsertFuture(calibrationConfig: CalibrationConfig): Future[Boolean] = {
    Future {
      val time = calibrationConfig.calibrationTime.
        map({
          val parser = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
          LocalTime.parse(_, parser)
        })
      val portConfigs = Json.toJson(calibrationConfig.pointConfigs).toString()
      DB localTx { implicit session =>
        sql"""
              MERGE INTO calibrationConfig AS target
                USING (SELECT ${calibrationConfig._id} AS id) AS source
                ON (target.id = source.id)
                WHEN MATCHED THEN
                UPDATE SET
                  instrumentIds = ${calibrationConfig.instrumentIds.mkString(",")},
                  calibrationTime = $time,
                  pointConfigs = $portConfigs
                WHEN NOT MATCHED THEN
                  INSERT ([id], [instrumentIds], [calibrationTime],[pointConfigs])
                VALUES
                (${calibrationConfig._id}
                ,${calibrationConfig.instrumentIds.mkString(",")}
                ,$time
                ,${Json.toJson(calibrationConfig.pointConfigs).toString()});
          """.execute().apply()
      }
    }
  }

  override def getListFuture: Future[Seq[CalibrationConfig]] = Future {
    DB readOnly { implicit session =>
      sql"SELECT * FROM calibrationConfig".map(mapper).list().apply()
    }
  }

  override def deleteFuture(name: String): Future[Boolean] = Future {
    DB localTx { implicit session =>
      sql"DELETE FROM calibrationConfig WHERE id = $name".execute().apply()
    }
  }
}
