package models.sql

import models.{Alarm, AlarmDB, AlertEmailSender, LineNotify, LoggerConfig}
import play.api.libs.mailer.MailerClient
import scalikejdbc._

import java.time.Instant
import java.util.Date
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class AlarmOp @Inject()(sqlServer: SqlServer, emailTargetOp: EmailTargetOp, mailerClient: MailerClient,
                        lineNotify: LineNotify, sysConfig: SysConfig) extends AlarmDB {
  private val tabName = "alarms"

  override def getAlarmsFuture(src: String, level: Int,
                               start: Date, end: Date): Future[Seq[Alarm]] =
    Future {
      implicit val session: DBSession = AutoSession
      sql"""
          SELECT *
          FROM [dbo].[alarms]
          Where src = $src and time >= $start and time < $end and [level] = $level
          Order by time desc
         """.map(mapper).list().apply()
    }

  override def getAlarmsFuture(level: Int, start: Date, end: Date): Future[List[Alarm]] =
    Future {
      implicit val session: DBSession = AutoSession
      sql"""
          SELECT *
          FROM [dbo].[alarms]
          Where time >= $start and time < $end and [level] = $level
          Order by time desc
         """.map(mapper).list().apply()
    }

  override def getAlarmsFuture(start: Date, end: Date): Future[Seq[Alarm]] = {
    implicit val session: DBSession = ReadOnlyAutoSession
    Future {
      sql"""
          SELECT *
          FROM [dbo].[alarms]
          Where time >= $start and time < $end
          Order by time desc
         """.map(mapper).list().apply()
    }
  }

  init()

  private def mapper(rs: WrappedResultSet) =
    Alarm(rs.timestamp("time"),
      rs.string("src"),
      rs.int("level"),
      rs.string("desc"))

  override def log(src: String, level: Int, desc: String, coldPeriod: Int = 30): Unit = {
    val ar = Alarm(Date.from(Instant.now), src, level, desc)
    logFilter(ar, coldPeriod)
  }

  private def logFilter(ar: Alarm, coldPeriod: Int = 30): Unit = {
    val start = Date.from(Instant.ofEpochMilli(ar.time.getTime).minusSeconds(coldPeriod * 60))
    val end = ar.time
    implicit val session: DBSession = AutoSession
    val countOpt =
      sql"""
          Select Count(*)
          FROM [dbo].[alarms]
          Where [time] >= $start and [time] < $end and [src] = ${ar.src} and [desc] = ${ar.desc}
         """.map(rs => rs.int(1)).first().apply()
    for (count <- countOpt if count == 0) {
      sql"""
             INSERT INTO [dbo].[alarms]
                ([time],[src],[level],[desc])
            VALUES
            (${ar.time}, ${ar.src}, ${ar.level}, ${ar.desc})
             """.execute().apply()

      if (ar.level >= Alarm.Level.ERR) {
        if (LoggerConfig.config.alertEmail)
        emailTargetOp.getList().foreach { emailTargets =>
          val emails = emailTargets.map(_._id)
          AlertEmailSender.sendAlertMail(mailerClient = mailerClient)("警報通知", emails, ar.desc)
        }

        for(token <- sysConfig.getLineToken if token.nonEmpty) {
          lineNotify.notify(token, ar.desc)
        }
      }
    }
  }

  private def init()(implicit session: DBSession = AutoSession): Unit = {
    if (!sqlServer.getTables().contains(tabName)) {
      sql"""
           CREATE TABLE [dbo].[alarms](
	                      [id] [bigint] IDENTITY(1,1) NOT NULL,
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
  }
}
