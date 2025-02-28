package models.sql

import com.github.nscala_time.time.Imports
import models.InstrumentStatusDB
import models.InstrumentStatusDB.{InstrumentStatus, Status}
import play.api.libs.json.Json
import scalikejdbc._

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class InstrumentStatusOp @Inject()(sqlServer: SqlServer) extends InstrumentStatusDB {
  private val tabName = "instrumentStatus"

  override def log(is: InstrumentStatus): Unit = {
    implicit val session: DBSession = AutoSession
    val statusList = Json.toJson(is.statusList).toString()
    sql"""
          INSERT INTO [dbo].[instrumentStatus]
           ([time]
           ,[instID]
           ,[statusList])
     VALUES
           (${is.time}
           ,${is.instID}
           ,$statusList)
         """.update().apply()
  }

  init()

  override def query(id: String, start: Imports.DateTime, end: Imports.DateTime): Seq[InstrumentStatus] = {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
         Select *
         From [dbo].[instrumentStatus]
         Where [instID] = $id and [time] >= ${start.toDate} and [time] < ${end.toDate}
         """.map(mapper).list().apply()
  }

  override def queryFuture(start: Imports.DateTime, end: Imports.DateTime): Future[Seq[InstrumentStatus]] =
    Future {
      implicit val session: DBSession = ReadOnlyAutoSession
      sql"""
         Select *
         From [dbo].[instrumentStatus]
         Where [time] >= ${start.toDate} and [time] < ${end.toDate}
         """.map(mapper).list().apply()
    }

  private def mapper(rs: WrappedResultSet): InstrumentStatus = {
    val statusList = Json.parse(rs.string("statusList")).validate[Seq[Status]].asOpt.getOrElse(Seq.empty[Status])
    InstrumentStatus(rs.timestamp("time"), instID = rs.string("instID"), statusList)
  }

  private def init()(implicit session: DBSession = AutoSession): Unit = {
    if (!sqlServer.getTables().contains(tabName)) {
      sql"""
          CREATE TABLE [dbo].[instrumentStatus](
	          [id] [bigint] IDENTITY(1,1) NOT NULL,
	          [time] [datetime2](7) NOT NULL,
	          [instID] [nvarchar](50) NOT NULL,
	          [statusList] [nvarchar](max) NOT NULL,
            CONSTRAINT [PK_instrumentStatus] PRIMARY KEY CLUSTERED
            (
	            [id] ASC
            )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON) ON [PRIMARY]
          ) ON [PRIMARY] TEXTIMAGE_ON [PRIMARY]
           """.execute().apply()
    }
  }
}
