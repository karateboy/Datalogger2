package models.sql

import com.mongodb.client.result.{DeleteResult, UpdateResult}
import models.{AlarmRule, AlarmRuleDb}
import scalikejdbc._

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class AlarmRuleOp @Inject()(sqlServer: SqlServer) extends AlarmRuleDb {
  private val tabName = "alarmRules"

  private def init()(implicit session: DBSession = AutoSession): Unit = {
    if (!sqlServer.getTables().contains(tabName)) {
      sql"""
       CREATE TABLE [dbo].[alarmRules](
        [id] [nvarchar](50) NOT NULL,
        [monitors] [nvarchar](1024) NOT NULL,
        [monitorTypes] [nvarchar](1024) NOT NULL,
        [max] [float] NULL,
        [min] [float] NULL,
        [alarmLevel] [int] NOT NULL,
        [enable] [bit] NOT NULL,
        [startTime] [time](2) NULL,
        [endTime] [time](2) NULL,
        [tableTypes] [nvarchar](16) NOT NULL,
        [messageTemplate] [nvarchar](128) NULL,
        [coldPeriod] [int] NULL,
       CONSTRAINT [PK_alarmRules] PRIMARY KEY CLUSTERED
      (
        [id] ASC
      )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON) ON [PRIMARY]
      ) ON [PRIMARY]
           """.execute().apply()
    } else {
      if (!sqlServer.getColumnNames(tabName).contains("messageTemplate")) {
        sql"""
         ALTER TABLE [dbo].[alarmRules]
         ADD [messageTemplate] [nvarchar](128) NULL
         """.execute().apply()
      }
      if (!sqlServer.getColumnNames(tabName).contains("coldPeriod")) {
        sql"""
         ALTER TABLE [dbo].[alarmRules]
         ADD [coldPeriod] [int] NULL
         """.execute().apply()
      }
    }
  }

  init()

  private def mapper(rs: WrappedResultSet) =
    AlarmRule(rs.string("id"),
      rs.string("monitorTypes").split(",").toSeq,
      rs.string("monitors").split(",").toSeq,
      rs.doubleOpt("max"),
      rs.doubleOpt("min"),
      rs.int("alarmLevel"),
      rs.string("tableTypes").split(",").toSeq,
      rs.boolean("enable"),
      rs.stringOpt("startTime"),
      rs.stringOpt("endTime"),
      rs.stringOpt("messageTemplate"),
      rs.intOpt("coldPeriod")
    )

  override def getRulesAsync: Future[Seq[AlarmRule]] = Future {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
         Select *
         FROM [dbo].[alarmRules]
         """.map(mapper).list().apply()
  }

  override def upsertAsync(rule: AlarmRule): Future[UpdateResult] = Future {
    implicit val session: DBSession = AutoSession
    val ret = {
      sql"""
          UPDATE [dbo].[alarmRules]
          SET [monitors] = ${rule.monitors.mkString(",")}
            ,[monitorTypes] = ${rule.monitorTypes.mkString(",")}
            ,[max] = ${rule.max}
            ,[min] = ${rule.min}
            ,[alarmLevel] = ${rule.alarmLevel}
            ,[enable] = ${rule.enable}
            ,[startTime] = ${rule.startTime}
            ,[endTime] = ${rule.endTime}
            ,[tableTypes] = ${rule.tableTypes.mkString(",")}
            ,[messageTemplate] = ${rule.messageTemplate}
            ,[coldPeriod] = ${rule.coldPeriod}
          WHERE [id] = ${rule._id}
          IF(@@ROWCOUNT = 0)
            BEGIN
              INSERT INTO [dbo].[alarmRules]
                 ([id]
                 ,[monitors]
                 ,[monitorTypes]
                 ,[max]
                 ,[min]
                 ,[alarmLevel]
                 ,[enable]
                 ,[startTime]
                 ,[endTime]
                 ,[tableTypes]
                 ,[messageTemplate]
                 ,[coldPeriod])
              VALUES
                 (${rule._id}
                 ,${rule.monitors.mkString(",")}
                 ,${rule.monitorTypes.mkString(",")}
                 ,${rule.max}
                 ,${rule.min}
                 ,${rule.alarmLevel}
                 ,${rule.enable}
                 ,${rule.startTime}
                 ,${rule.endTime}
                 ,${rule.tableTypes.mkString(",")}
                 ,${rule.messageTemplate}
                 ,${rule.coldPeriod})
            END
          """.update().apply()
    }
    UpdateResult.acknowledged(ret, ret, null)
  }

  override def deleteAsync(_id: String): Future[DeleteResult] = Future {
    implicit val session: DBSession = AutoSession
    val ret =
      sql"""
         DELETE FROM [dbo].[alarmRules]
         WHERE [id] = ${_id}
         """.update().apply()
    DeleteResult.acknowledged(ret)
  }
}
