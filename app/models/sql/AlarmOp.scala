package models.sql

import com.github.nscala_time.time
import com.github.nscala_time.time.Imports
import com.github.nscala_time.time.Imports.DateTime
import com.mongodb.client.result.UpdateResult
import models.{Alarm, AlarmDB, Monitor}
import scalikejdbc._

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class AlarmOp @Inject()(sqlServer: SqlServer) extends AlarmDB {
  private val tabName = "alarms"

  override def getAlarmsFuture(src: String, level: Int,
                               start: time.Imports.DateTime, end: time.Imports.DateTime): Future[Seq[Alarm]] =
  Future {
    implicit val session: DBSession = AutoSession
    sql"""
          SELECT *
          FROM [dbo].[alarms]
          Where src = $src and time >= ${start.toDate} and time < ${end.toDate} and [level] >= $level
          Order by time desc
         """.map(mapper).list().apply()
  }

  override def getAlarmsFuture(level: Int, start: Imports.DateTime, end: Imports.DateTime): Future[List[Alarm]] =
  Future {
    implicit val session: DBSession = AutoSession
    sql"""
          SELECT *
          FROM [dbo].[alarms]
          Where time >= ${start.toDate} and time < ${end.toDate} and [level] >= $level
          Order by time desc
         """.map(mapper).list().apply()
  }

  override def getAlarmsFuture(start: Imports.DateTime, end: Imports.DateTime): Future[Seq[Alarm]] = {
    implicit val session: DBSession = ReadOnlyAutoSession
    Future {
      sql"""
          SELECT *
          FROM [dbo].[alarms]
          Where time >= ${start.toDate} and time < ${end.toDate}
          Order by time desc
         """.map(mapper).list().apply()
    }
  }

  init()

  private def mapper(rs: WrappedResultSet) =
    Alarm(rs.jodaDateTime("time").toDate,
      rs.string("src"),
      rs.int("level"),
      rs.string("desc"),
      rs.string("monitor")
    )

  override def log(src: String, level: Int, desc: String, coldPeriod: Int = 30): Unit = {
    val ar = Alarm(Date.from(Instant.now), src, level, desc, Monitor.activeId)
    logFilter(ar, coldPeriod)
  }

  private def logFilter(ar: Alarm, coldPeriod: Int = 30) = {
    val start = ar.time
    val end = Date.from(ar.time.toInstant.minus(coldPeriod, ChronoUnit.MINUTES))
    implicit val session: DBSession = AutoSession
    val countOpt =
      sql"""
          Select Count(*)
          FROM [dbo].[alarms]
          Where [time] >= ${start} and [time] < ${end} and [src] = ${ar.src} and [desc] = ${ar.desc}
         """.map(rs => rs.int(1)).first().apply()
    for (count <- countOpt) {
      if (count == 0) {
        sql"""
             INSERT INTO [dbo].[alarms]
                ([monitor],[time],[src],[level],[desc])
            VALUES
            (${ar.monitor}, ${ar.time}, ${ar.src}, ${ar.level}, ${ar.desc})
             """.execute().apply()
      }
    }
  }

  private def init()(implicit session: DBSession = AutoSession): Unit = {
    if (!sqlServer.getTables().contains(tabName)) {
      sql"""
           CREATE TABLE [dbo].[alarms](
	                      [id] [bigint] IDENTITY(1,1) NOT NULL,
                        [monitor] [nvarchar](50) NOT NULL,
	                      [time] [datetime2](7) NOT NULL,
	                      [src] [nvarchar](50) NOT NULL,
	                      [level] [int] NOT NULL,
	                      [desc] [nvarchar](1024) NOT NULL,
                        CONSTRAINT [PK_alarms] PRIMARY KEY CLUSTERED
              (
	              [id] ASC
              )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON) ON [PRIMARY]
          ) ON [PRIMARY]
           """.execute().apply()
    }
    sqlServer.addMonitorIfNotExist(tabName)
  }

  override def getLatestMonitorRecordTimeAsync(monitor: String): Future[Option[DateTime]] =
    sqlServer.getLatestMonitorRecordTimeAsync(tabName, monitor, "time")

  override def insertAlarms(alarms: Seq[Alarm]): Future[UpdateResult] = Future{
    val ret =
      DB localTx { implicit session =>
      val batchParams: Seq[Seq[Any]] = alarms.map(ar => Seq(ar.monitor, ar.time, ar.src, ar.level, ar.desc))
      sql"""
      INSERT INTO [dbo].[alarms]
      ([monitor], [time], [src], [level], [desc])
      VALUES (?, ?, ?, ?, ?)""".batch(batchParams: _*).apply()
    }
    val sum = ret.sum
    UpdateResult.acknowledged(sum, sum,null)
  }

  override def getMonitorAlarmsFuture(monitors: Seq[String], start: Date, end: Date): Future[Seq[Alarm]] = Future {
    implicit val session: DBSession = ReadOnlyAutoSession
    val monitorInClause = SQLSyntax.in(SQLSyntax.createUnsafely("monitor"), monitors)
      sql"""
          SELECT *
          FROM [dbo].[alarms]
          Where time >= ${start} and time < ${end} and $monitorInClause
          Order by time desc
         """.map(mapper).list().apply()
  }
}
