package models.sql

import com.mongodb.client.result.DeleteResult
import models.{MonitorTypeGroup, MonitorTypeGroupDb}
import scalikejdbc.{AutoSession, DBSession, scalikejdbcSQLInterpolationImplicitDef}

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

@Singleton
class MonitorTypeGroupOp @Inject()(sqlServer: SqlServer) extends MonitorTypeGroupDb {
  private val tabName = "monitorTypeGroups"

  private def getMonitorTypeGroupsSync = {
    import scalikejdbc._
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
        SELECT *
        FROM [dbo].[monitorTypeGroups]
        Order by name
       """.map(rs => models.MonitorTypeGroup(
      rs.string("_id"),
      rs.string("name"),
      rs.string("mts").split("/t").toSeq.filter(_.nonEmpty)
    )).list().apply()
  }

  private def upsertMonitorTypeGroupSync(mtg: models.MonitorTypeGroup): Boolean = {
    import scalikejdbc._
    implicit val session: DBSession = AutoSession
    val count =
      sql"""
        SELECT COUNT(*)
        FROM [dbo].[monitorTypeGroups]
        WHERE _id = ${mtg._id}
       """.map(rs => rs.int(1)).single().apply().getOrElse(0)
    if (count == 0) {
      sql"""
          INSERT INTO [dbo].[monitorTypeGroups] (_id, name, mts)
          VALUES (${mtg._id}, ${mtg.name}, ${mtg.mts.mkString("/t")})
         """.update().apply()
    } else {
      sql"""
          UPDATE [dbo].[monitorTypeGroups]
          SET name = ${mtg.name},
              mts = ${mtg.mts.mkString("/t")}
          WHERE _id = ${mtg._id}
         """.update().apply()
    }
    true
  }

  private def deleteMonitorTypeGroupSync(_id: String): DeleteResult = {
    import scalikejdbc._
    implicit val session: DBSession = AutoSession
    val ret =
      sql"""
        DELETE FROM [dbo].[monitorTypeGroups]
        WHERE _id = ${_id}
       """.update().apply()
    DeleteResult.acknowledged(ret)
  }

  private def init()(implicit session: DBSession = AutoSession): Unit = {
    if (!sqlServer.getTables().contains(tabName)) {
      sql"""
           CREATE TABLE [dbo].[monitorTypeGroups](
	          [_id] [nvarchar](50) NOT NULL,
	          [name] [nvarchar](50) NOT NULL,
	          [mts] [nvarchar](max) NOT NULL,
          CONSTRAINT [PK_monitorTypeGroups] PRIMARY KEY CLUSTERED
        (
	        [_id] ASC
        )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON) ON [PRIMARY]
        ) ON [PRIMARY] TEXTIMAGE_ON [PRIMARY]
           """.execute().apply()
    }
  }

  init()

  override def getMonitorTypeGroups: Future[Seq[MonitorTypeGroup]] = Future.successful(getMonitorTypeGroupsSync)

  override def upsertMonitorTypeGroup(mtg: MonitorTypeGroup): Future[Boolean] = Future.successful(upsertMonitorTypeGroupSync(mtg))

  override def deleteMonitorTypeGroup(_id: String): Future[DeleteResult] = Future.successful(deleteMonitorTypeGroupSync(_id))
}
