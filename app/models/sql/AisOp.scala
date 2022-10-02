package models.sql

import com.mongodb.client.result.{InsertOneResult, UpdateResult}
import models.{AisDB, AisData}
import org.mongodb.scala.result.{InsertOneResult, UpdateResult}

import java.util.Date
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scalikejdbc._

import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class AisOp @Inject()(sqlServer: SqlServer) extends AisDB {
  private val tabName = "ais_data"

  private def init()(implicit session: DBSession = AutoSession): Unit = {
    if (!sqlServer.getTables().contains(tabName)) {
      sql"""
       CREATE TABLE [dbo].[ais_data](
        [monitor] [nvarchar](50) NOT NULL,
        [time] [datetime2](7) NOT NULL,
        [json] [nvarchar](max) NOT NULL,
        [respType] [nvarchar](50) NOT NULL,
       CONSTRAINT [PK_ais_data] PRIMARY KEY CLUSTERED
      (
        [monitor] ASC,
        [time] ASC
      )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY]
      ) ON [PRIMARY] TEXTIMAGE_ON [PRIMARY]
       """.execute().apply()
    }

    if (!sqlServer.getColumnNames(tabName).contains("respType")) {
      sql"""
          Alter Table ais_data
          Add [respType] [nvarchar](50);
         """.execute().apply()
    }
  }

  init()

  private def mapper(rs: WrappedResultSet) =
    AisData(rs.string("monitor"),
      rs.date("time"),
      rs.string("json"),
      rs.stringOpt("respType").getOrElse("simple"))

  override def getAisData(monitor: String, start: Date, end: Date): Future[Seq[AisData]] = Future {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
        SELECT *
        FROM [dbo].[ais_data]
        Where [monitor] = $monitor and time >= ${start} and time < ${end}
        Order by time desc
       """.map(mapper).list().apply()
  }

  override def insertAisData(aisData: AisData): Future[InsertOneResult] = Future {
    DB localTx {
      implicit session =>
        sql"""
            INSERT INTO [dbo].[ais_data]
                 ([monitor]
                 ,[time]
                 ,[json]
                 ,[respType])
           VALUES
                 (${aisData.monitor}
                 ,${aisData.time}
                 ,${aisData.json}
                 ,${aisData.respType})
        """.execute().apply()
    }

    InsertOneResult.unacknowledged()
  }

  override def getLatestData(monitor: String): Future[Option[AisData]] = Future {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
         SELECT TOP 1 *
         FROM [dbo].[ais_data]
         WHERE [monitor] = $monitor
         ORDER BY [time] desc
         """.map(mapper).first().apply()
  }

  override def getNearestAisDataInThePast(monitor: String, respType: String, start: Date): Future[Option[AisData]] = Future {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
         SELECT TOP 1 *
         FROM [dbo].[ais_data]
         WHERE [monitor] = $monitor and [respType] = $respType and [time] <= $start
         ORDER BY [time] desc
         """.map(mapper).first().apply()
  }
}
