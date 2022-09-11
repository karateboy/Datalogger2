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
class MonitorTypeOp @Inject()(sqlServer: SqlServer) extends MonitorTypeDB {
  private val tabName = "monitorType"

  init()

  override def upsertItemFuture(mt: MonitorType): Future[UpdateResult] = Future {
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
                ,[zd_law] = ${mt.zd_law}
                ,[span] = ${mt.span}
                ,[span_dev_law] = ${mt.span_dev_law}
                ,[measuringBy] = ${measuringBy}
                ,[acoustic] = ${mt.acoustic}
                ,[spectrum] = ${mt.spectrum}
                ,[levels] = ${levels}
                ,[calibrate] = ${mt.calibrate}
                ,[accumulated] = ${mt.accumulated}
                ,[fixedM] = ${mt.fixedM}
                ,[fixedB] = ${mt.fixedB}
                ,[overLawSignalType] = ${mt.overLawSignalType}
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
           ,[zd_law]
           ,[span]
           ,[span_dev_law]
           ,[measuringBy]
           ,[acoustic]
           ,[spectrum]
           ,[levels]
           ,[calibrate]
           ,[accumulated]
           ,[fixedM]
           ,[fixedB]
           ,[overLawSignalType])
          VALUES
              (${mt._id}
                ,${mt.desp}
                ,${mt.unit}
                ,${mt.prec}
                ,${mt.order}
                ,${mt.signalType}
                ,${mt.std_law}
                ,${mt.zd_law}
                ,${mt.span}
                ,${mt.span_dev_law}
                ,${measuringBy}
                ,${mt.acoustic}
                ,${mt.spectrum}
                ,${levels}
                ,${mt.calibrate}
                ,${mt.accumulated}
                ,${mt.fixedM}
                ,${mt.fixedB}
                ,${mt.overLawSignalType})
            END
         """.update().apply()
    UpdateResult.acknowledged(ret, ret, null)
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
      zd_law = rs.doubleOpt("zd_law"),
      span = rs.doubleOpt("span"),
      span_dev_law = rs.doubleOpt("span_dev_law"),
      measuringBy = measuringBy,
      acoustic = rs.booleanOpt("acoustic"),
      spectrum = rs.booleanOpt("spectrum"),
      levels = levels,
      calibrate = rs.booleanOpt("calibrate"),
      accumulated = rs.booleanOpt("accumulated"),
      fixedM = rs.doubleOpt("fixedM"),
      fixedB = rs.doubleOpt("fixedB"),
      overLawSignalType = rs.stringOpt("overLawSignalType"))
  }

  override def deleteItemFuture(_id: String): Unit = {
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
	          [zd_law] [float] NULL,
	          [span] [float] NULL,
	          [span_dev_law] [float] NULL,
	          [measuringBy] [nvarchar](50) NULL,
	          [acoustic] [bit] NULL,
	          [spectrum] [bit] NULL,
	          [levels] [nvarchar](50) NULL,
	          [calibrate] [bit] NULL,
	          [accumulated] [bit] NULL,
            [fixedM] [float] NULL,
            [fixedB] [float] NULL,
            [overLawSignalType] [nvarchar](50),
        CONSTRAINT [PK_monitorType] PRIMARY KEY CLUSTERED
        (
	        [id] ASC
        )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON) ON [PRIMARY]
      ) ON [PRIMARY]
           """.execute().apply()
      defaultMonitorTypes.foreach(mt => waitReadyResult(upsertItemFuture(mt)))
    }

    if(!sqlServer.getColumnNames(tabName).contains("fixedM")){
      sql"""
          Alter Table monitorType
          Add [fixedM] float;
         """.execute().apply()
    }

    if(!sqlServer.getColumnNames(tabName).contains("fixedB")){
      sql"""
          Alter Table monitorType
          Add [fixedB] float;
         """.execute().apply()
    }

    if(!sqlServer.getColumnNames(tabName).contains("overLawSignalType")){
      sql"""
          Alter Table monitorType
          Add [overLawSignalType] [nvarchar](50);
         """.execute().apply()
    }

    refreshMtv()
  }
}
