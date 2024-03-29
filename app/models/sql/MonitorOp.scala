package models.sql

import com.mongodb.client.result.DeleteResult
import models.{LoggerConfig, Monitor, MonitorDB}
import scalikejdbc._

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class MonitorOp @Inject()(sqlServer: SqlServer, sysConfig: SysConfig) extends MonitorDB {
  private val tabName = "monitor"

  override def deleteMonitor(_id: String): Future[DeleteResult] = Future {
    implicit val session: DBSession = AutoSession
    val ret =
      sql"""
         DELETE FROM [dbo].[monitor]
         Where [id] = ${_id}
         """.update().apply()
    DeleteResult.acknowledged(ret)
  }

  init()

  override def mList: List[Monitor] = {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
         Select *
         FROM [dbo].[monitor]
         """.map(mapper).list().apply()
  }

  private def mapper(rs: WrappedResultSet) = Monitor(rs.string("id"),
    rs.string("name"),
    rs.doubleOpt("lat"),
    rs.doubleOpt("lng")
  )

  private def init()(implicit session: DBSession = AutoSession): Unit = {
    if (!sqlServer.getTables().contains(tabName)) {
      sql"""
          CREATE TABLE [dbo].[monitor](
	          [id] [nvarchar](50) NOT NULL,
	          [name] [nvarchar](256) NOT NULL,
	          [lat] [float] NULL,
	          [lng] [float] NULL,
          CONSTRAINT [PK_monitor] PRIMARY KEY CLUSTERED
          (
	          [id] ASC
          )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON) ON [PRIMARY]
          ) ON [PRIMARY]
           """.execute().apply()

      if(LoggerConfig.config.selfMonitor)
        upsert(Monitor.defaultMonitor)

      refresh(sysConfig)
    } else
      refresh(sysConfig)
  }

  override def upsert(m: Monitor): Unit = {
    implicit val session: DBSession = AutoSession
    map = map + (m._id -> m)
    sql"""
          UPDATE [dbo].[monitor]
            SET [name] = ${m.desc}
                ,[lat] = ${m.lat}
                ,[lng] = ${m.lng}
                Where [id] = ${m._id}
            IF(@@ROWCOUNT = 0)
            BEGIN
              INSERT INTO [dbo].[monitor]
              ([id]
                ,[name]
                ,[lat]
                ,[lng])
              VALUES
              (${m._id}
              ,${m.desc}
              ,${m.lat}
              ,${m.lng})
            END
         """.update().apply()
  }
}
