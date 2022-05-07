package models.sql

import com.github.nscala_time.time.Imports
import models.{Alarm, AlarmDB}
import scalikejdbc._

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

@Singleton
class AlarmOp @Inject()(sqlServer: SqlServer) extends AlarmDB {
  private val tabName = "alarms"

  override def getAlarms(level: Int, start: Imports.DateTime, end: Imports.DateTime): List[Alarm] = {
    implicit val session: DBSession = AutoSession
    sql"""
          SELECT *
          FROM [dbo].[alarms]
          Where time >= ${start.toDate} and time < ${end.toDate}
         """.map(rs =>
      Alarm(rs.jodaDateTime("time"),
        rs.string("src"),
        rs.int("level"),
        rs.string("desc"))).list().apply()
  }

  init()

  override def getAlarmsFuture(start: Imports.DateTime, end: Imports.DateTime): Future[Seq[Alarm]] = ???

  override def log(src: String, level: Int, desc: String, coldPeriod: Int): Unit = ???

  private def init()(implicit session: DBSession = AutoSession): Unit = {
    if (!sqlServer.getTables().contains(tabName)) {
      sql"""
           CREATE TABLE [dbo].[alarms](
	                      [id] [bigint] IDENTITY(1,1) NOT NULL,
	                      [time] [datetime2](7) NOT NULL,
	                      [src] [nvarchar](50) NOT NULL,
	                      [level] [int] NOT NULL,
	                      [desc] [nvarchar](50) NOT NULL,
                        CONSTRAINT [PK_alarms] PRIMARY KEY CLUSTERED
              (
	              [id] ASC
              )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON) ON [PRIMARY]
          ) ON [PRIMARY]
           """.execute().apply()
    }
  }
}
