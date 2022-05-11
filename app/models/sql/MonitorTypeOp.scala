package models.sql

import com.mongodb.client.result.UpdateResult
import models.ModelHelper.waitReadyResult
import models.{AlarmDB, MonitorType, MonitorTypeDB}
import play.api.Logger
import scalikejdbc._

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class MonitorTypeOp @Inject()(sqlServer: SqlServer, alarmDB: AlarmDB, recordOp: RecordOp) extends MonitorTypeDB {
  private val tabName = "monitorType"

  override def logDiMonitorType(mt: String, v: Boolean): Unit = {
    if (!signalMtvList.contains(mt))
      Logger.warn(s"${mt} is not DI monitor type!")

    val previousValue = diValueMap.getOrElse(mt, !v)
    diValueMap = diValueMap + (mt -> v)
    if (previousValue != v) {
      val mtCase = map(mt)
      if (v)
        alarmDB.log(alarmDB.src(), alarmDB.Level.WARN, s"${mtCase.desp}=>觸發", 1)
      else
        alarmDB.log(alarmDB.src(), alarmDB.Level.INFO, s"${mtCase.desp}=>解除", 1)
    }
  }

  init()

  override def addMeasuring(mt: String, instrumentId: String, append: Boolean): Future[UpdateResult] = {
    map(mt).addMeasuring(instrumentId, append)
    recordOp.ensureMonitorType(mt)
    upsertMonitorTypeFuture(map(mt))
  }

  override def getList: List[MonitorType] = {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
          SELECT *
          FROM [dbo].[monitorType]
         """.map(mapper).list().apply()
  }

  private def mapper(rs: WrappedResultSet): MonitorType = {
    val measuringBy = rs.stringOpt("measuringBy").map(_.split(",").filter(_.nonEmpty).toList)
    val levels = rs.stringOpt("levels").map(_.split(",").filter(_.nonEmpty).toSeq.map(_.toDouble))
    MonitorType(_id = rs.string("id"),
      desp = rs.string("desp"),
      unit = rs.string("unit"),
      prec = rs.int("prec"),
      order = rs.int("order"),
      signalType = rs.boolean("signalType"),
      std_law = rs.doubleOpt("std_law"),
      std_internal = rs.doubleOpt("std_internal"),
      zd_internal = rs.doubleOpt("zd_internal"),
      zd_law = rs.doubleOpt("zd_law"),
      span = rs.doubleOpt("span"),
      span_dev_internal = rs.doubleOpt("span_dev_internal"),
      span_dev_law = rs.doubleOpt("span_dev_law"),
      measuringBy = measuringBy,
      acoustic = rs.booleanOpt("acoustic"),
      spectrum = rs.booleanOpt("spectrum"),
      levels = levels,
      calibrate = rs.booleanOpt("calibrate"),
      accumulated = rs.booleanOpt("accumulated"))
  }

  override def upsertMonitorTypeFuture(mt: MonitorType): Future[UpdateResult] = Future {
    implicit val session: DBSession = AutoSession
    val measuringBy = mt.measuringBy.map(instList => instList.mkString(","))
    val levels = mt.levels.map(levelValues => levelValues.mkString(","))

    val ret =
      sql"""
          UPDATE [dbo].[monitorType]
            SET [desp] = ${mt.desp}
                ,[unit] = ${mt.unit}
                ,[prec] = ${mt.prec}
                ,[order] = ${mt.order}
                ,[signalType] = ${mt.signalType}
                ,[std_law] = ${mt.std_law}
                ,[std_internal] = ${mt.std_internal}
                ,[zd_law] = ${mt.zd_law}
                ,[zd_internal] = ${mt.zd_internal}
                ,[span] = ${mt.span}
                ,[span_dev_law] = ${mt.span_dev_law}
                ,[span_dev_internal] = ${mt.span_dev_internal}
                ,[measuringBy] = ${measuringBy}
                ,[acoustic] = ${mt.acoustic}
                ,[spectrum] = ${mt.spectrum}
                ,[levels] = ${levels}
                ,[calibrate] = ${mt.calibrate}
                ,[accumulated] = ${mt.accumulated}
            WHERE [id] = ${mt._id}
          IF(@@ROWCOUNT = 0)
            BEGIN
              INSERT INTO [dbo].[monitorType]
           ([id]
           ,[desp]
           ,[unit]
           ,[prec]
           ,[order]
           ,[signalType]
           ,[std_law]
           ,[std_internal]
           ,[zd_law]
           ,[zd_internal]
           ,[span]
           ,[span_dev_law]
           ,[span_dev_internal]
           ,[measuringBy]
           ,[acoustic]
           ,[spectrum]
           ,[levels]
           ,[calibrate]
           ,[accumulated])
          VALUES
              (${mt._id}
                ,${mt.desp}
                ,${mt.unit}
                ,${mt.prec}
                ,${mt.order}
                ,${mt.signalType}
                ,${mt.std_law}
                ,${mt.std_internal}
                ,${mt.zd_law}
                ,${mt.zd_internal}
                ,${mt.span}
                ,${mt.span_dev_law}
                ,${mt.span_dev_internal}
                ,${measuringBy}
                ,${mt.acoustic}
                ,${mt.spectrum}
                ,${levels}
                ,${mt.calibrate}
                ,${mt.accumulated})
            END
         """.update().apply()
    UpdateResult.acknowledged(ret, ret, null)
  }

  override def deleteMonitorType(_id: String): Unit = {
    implicit val session: DBSession = AutoSession
    sql"""
        DELETE FROM [dbo].[monitorType]
        WHERE [id] = ${_id}
         """.update().apply()
  }

  private def init()(implicit session: DBSession = AutoSession): Unit = {
    if (!sqlServer.getTables().contains(tabName)) {
      sql"""
          CREATE TABLE [dbo].[monitorType](
	          [id] [nvarchar](50) NOT NULL,
	          [desp] [nvarchar](50) NOT NULL,
	          [unit] [nvarchar](50) NOT NULL,
	          [prec] [int] NOT NULL,
	          [order] [int] NOT NULL,
	          [signalType] [bit] NOT NULL,
	          [std_law] [float] NULL,
	          [std_internal] [float] NULL,
	          [zd_law] [float] NULL,
	          [zd_internal] [float] NULL,
	          [span] [float] NULL,
	          [span_dev_law] [float] NULL,
	          [span_dev_internal] [float] NULL,
	          [measuringBy] [nvarchar](50) NULL,
	          [acoustic] [bit] NULL,
	          [spectrum] [bit] NULL,
	          [levels] [nvarchar](50) NULL,
	          [calibrate] [bit] NULL,
	          [accumulated] [bit] NULL,
        CONSTRAINT [PK_monitorType] PRIMARY KEY CLUSTERED
        (
	        [id] ASC
        )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON) ON [PRIMARY]
      ) ON [PRIMARY]
           """.execute().apply()
      defaultMonitorTypes.foreach(mt => waitReadyResult(upsertMonitorTypeFuture(mt)))
    }
    refreshMtv
  }
}
