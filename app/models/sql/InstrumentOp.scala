package models.sql

import com.mongodb.client.result.UpdateResult
import models.Protocol.ProtocolParam
import models.{Instrument, InstrumentDB, InstrumentStatusType}
import play.api.libs.json.Json
import scalikejdbc._

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class InstrumentOp @Inject()(sqlServer: SqlServer) extends InstrumentDB {
  private val tabName = "instrument"

  override def upsertInstrument(inst: Instrument): Boolean = {
    implicit val session: DBSession = AutoSession
    val protocol = Json.toJson(inst.protocol).toString()
    val statusType = for (st <- inst.statusType) yield
      Json.toJson(st).toString()
    val ret =
      sql"""
            UPDATE [dbo].[instrument]
              SET [instType] = ${inst.instType}
                  ,[protocol] = ${protocol}
                  ,[param] = ${inst.param}
                  ,[active] = ${inst.active}
                  ,[state] = ${inst.state}
                  ,[statusType] = $statusType
              WHERE [id] = ${inst._id}
            IF(@@ROWCOUNT = 0)
            BEGIN
              INSERT INTO [dbo].[instrument]
                ([id]
                  ,[instType]
                  ,[protocol]
                  ,[param]
                  ,[active]
                  ,[state]
                  ,[statusType])
              VALUES
                (${inst._id}
                ,${inst.instType}
                ,${protocol}
                ,${inst.param}
                ,${inst.active}
                ,${inst.state}
                ,${statusType})
            END
          """.update().apply()
    ret == 1
  }

  init()

  override def getAllInstrumentFuture: Future[Seq[Instrument]] = Future {
    getInstrumentList()
  }

  override def getInstrumentList(): Seq[Instrument] = {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
          Select *
          FROM [dbo].[instrument]
         """.map(mapper).list().apply()
  }

  override def getInstrumentFuture(id: String): Future[Instrument] = Future {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
          Select *
          FROM [dbo].[instrument]
          Where [id] = $id
         """.map(mapper).first().apply().get
  }

  private def mapper(rs: WrappedResultSet) = {
    val protocol = Json.parse(rs.string("protocol")).validate[ProtocolParam].asOpt.get
    val instrumentStatusTypeOpt =
      for (ist <- rs.stringOpt("statusType")) yield {
        Json.parse(ist).validate[List[InstrumentStatusType]].asOpt
      }
    Instrument(rs.string("id"), rs.string("instType"),
      protocol, rs.string("param"), rs.boolean("active"),
      rs.string("state"),
      instrumentStatusTypeOpt.flatten)
  }

  override def delete(id: String): Boolean = {
    implicit val session: DBSession = AutoSession
    sql"""
          DELETE FROM [dbo].[instrument]
          WHERE [id] = $id
         """.update().apply() == 1
  }

  override def activate(id: String): Future[UpdateResult] = Future {
    implicit val session: DBSession = AutoSession
    val ret =
      sql"""
      UPDATE [dbo].[instrument]
        SET [active] = ${true}
      WHERE [id] = $id
         """.update().apply()
    UpdateResult.acknowledged(ret, ret, null)
  }

  override def deactivate(id: String): Future[UpdateResult] = Future {
    implicit val session: DBSession = AutoSession
    val ret =
      sql"""
      UPDATE [dbo].[instrument]
        SET [active] = ${false}
      WHERE [id] = $id
         """.update().apply()
    UpdateResult.acknowledged(ret, ret, null)
  }

  override def setState(id: String, state: String): Future[UpdateResult] = Future {
    implicit val session: DBSession = AutoSession
    val ret =
      sql"""
      UPDATE [dbo].[instrument]
        SET [state] = ${state}
      WHERE [id] = $id
         """.update().apply()
    UpdateResult.acknowledged(ret, ret, null)
  }

  override def updateStatusType(id: String, statusList: List[InstrumentStatusType]): Future[UpdateResult] = Future {
    implicit val session: DBSession = AutoSession
    val statusType = Json.toJson(statusList).toString()
    val ret =
      sql"""
      UPDATE [dbo].[instrument]
        SET [statusType] = ${statusType}
      WHERE [id] = $id
         """.update().apply()
    UpdateResult.acknowledged(ret, ret, null)
  }

  override def getInstrument(id: String): Seq[Instrument] = {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
          Select *
          FROM [dbo].[instrument]
          Where [id] = $id
         """.map(mapper).list().apply()
  }

  private def init()(implicit session: DBSession = AutoSession): Unit = {
    if (!sqlServer.getTables().contains(tabName)) {
      sql"""
          CREATE TABLE [dbo].[instrument](
	          [id] [nvarchar](50) NOT NULL,
	          [instType] [nvarchar](50) NOT NULL,
	          [protocol] [nvarchar](128) NOT NULL,
	          [param] [nvarchar](1024) NOT NULL,
	          [active] [bit] NOT NULL,
	          [state] [nvarchar](10) NOT NULL,
	          [statusType] [nvarchar](max) NULL,
            CONSTRAINT [PK_instrument] PRIMARY KEY CLUSTERED
          (
	          [id] ASC
          )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON) ON [PRIMARY]
          ) ON [PRIMARY] TEXTIMAGE_ON [PRIMARY]
           """.execute().apply()
    }
  }
}
