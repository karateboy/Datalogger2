package models.sql

import com.mongodb.client.result.UpdateResult
import models.{InstrumentStatusType, InstrumentStatusTypeDB, InstrumentStatusTypeMap}
import org.mongodb.scala.result.UpdateResult
import play.api.libs.json.Json
import scalikejdbc.{AutoSession, DB, DBSession, WrappedResultSet, scalikejdbcSQLInterpolationImplicitDef}

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class InstrumentStatusTypeOp @Inject()(sqlServer: SqlServer) extends InstrumentStatusTypeDB {
  private val tabName = "instrumentStatusTypes"

  private def mapper(rs: WrappedResultSet): InstrumentStatusTypeMap = {
    val statusTypeSeq = Json.parse(rs.string("statusTypeSeq")).validate[Seq[InstrumentStatusType]].get
    InstrumentStatusTypeMap(monitor = Some(rs.string("monitor")),
      instrumentId = rs.string("instrumentId"),
      statusTypeSeq = statusTypeSeq)
  }

  private def init()(implicit session: DBSession = AutoSession): Unit = {
    if (!sqlServer.getTables().contains(tabName)) {
      sql"""
      CREATE TABLE [dbo].[instrumentStatusTypes](
        [monitor] [nvarchar](50) NOT NULL,
        [instrumentId] [nvarchar](50) NOT NULL,
        [statusTypeSeq] [nvarchar](max) NOT NULL,
       CONSTRAINT [PK_instrumentStatusTypes] PRIMARY KEY CLUSTERED
      (
        [monitor] ASC,
        [instrumentId] ASC
      )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY]
      ) ON [PRIMARY] TEXTIMAGE_ON [PRIMARY]
      """.execute().apply()
    }
  }

  init()

  override def getAllInstrumentStatusTypeListAsync(monitor: String): Future[Seq[InstrumentStatusTypeMap]] =
    Future {
      implicit val session: DBSession = AutoSession
      sql"""
          SELECT *
          FROM [dbo].[instrumentStatusTypes]
          Where monitor = ${monitor}
         """.map(mapper).list().apply()
    }


  override def upsertInstrumentStatusTypeMapAsync(monitor: String, maps: Seq[InstrumentStatusTypeMap]): Future[UpdateResult] =
    Future {
      implicit val session: DBSession = AutoSession

      sql"""
           DELETE FROM [dbo].[instrumentStatusTypes]
           WHERE monitor = $monitor
           """.execute().apply()

      val batchParams: Seq[Seq[String]] = maps.map(istMap =>
        Seq(monitor, istMap.instrumentId, Json.toJson(istMap.statusTypeSeq).toString()))

      sql"""
            INSERT INTO [dbo].[instrumentStatusTypes]
                     ([monitor]
                     ,[instrumentId]
                     ,[statusTypeSeq])
               VALUES
                     (?, ?, ?)""".batch(batchParams: _*).apply()


      UpdateResult.acknowledged(maps.size, maps.size, null)
    }
}
