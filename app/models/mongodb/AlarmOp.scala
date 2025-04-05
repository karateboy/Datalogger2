package models.mongodb

import com.github.nscala_time.time.Imports._
import models.Alarm.Level
import models.ModelHelper._
import models._
import org.bson.codecs.configuration.CodecRegistry
import org.mongodb.scala._
import play.api._
import play.api.libs.mailer.MailerClient

import java.time.Instant
import java.util.Date
import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.implicitConversions

@Singleton
class AlarmOp @Inject()(mongodb: MongoDB, mailerClient: MailerClient, emailTargetOp: EmailTargetOp,
                        lineNotify: LineNotify, sysConfig: SysConfig) extends AlarmDB {

  val logger: Logger = Logger(getClass)

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  lazy val codecRegistry: CodecRegistry = fromRegistries(fromProviders(classOf[Alarm]), DEFAULT_CODEC_REGISTRY)
  lazy val colName = "alarms"
  lazy private val collection = mongodb.database.getCollection[Alarm](colName).withCodecRegistry(codecRegistry)

  private def init(): Unit = {
    import org.mongodb.scala.model.Indexes._
    for (colNames <- mongodb.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(colName)) {
        val f = mongodb.database.createCollection(colName).toFuture()
        f.failed.foreach(errorHandler)
        f.foreach(_ => collection.createIndex(ascending("time", "level", "src")))
      }
    }
  }

  init()

  import org.mongodb.scala.model.Filters._
  import org.mongodb.scala.model.Sorts._

  override def getAlarmsFuture(level: Int, start: Date, end: Date): Future[Seq[Alarm]] = {
    val f = collection.find(and(gte("time", start), lt("time", end), equal("level", level)))
      .sort(descending("time")).toFuture()

    f.failed.foreach(errorHandler)
    f
  }

  override def getAlarmsFuture(src: String, level: Int, start: Date, end: Date): Future[Seq[Alarm]] = {
    val f = collection.find(and(equal("src", src),
      gte("time", start),
      lt("time", end),
      equal("level", level))).sort(descending("time")).toFuture()

    f.failed.foreach(errorHandler)
    f
  }

  override def getAlarmsFuture(start: Date, end: Date): Future[Seq[Alarm]] =
    collection.find(and(gte("time", start), lt("time", end))).sort(descending("time")).toFuture()


  private def logFilter(ar: Alarm, coldPeriod: Int = 30): Unit = {
    def insertAlarm(): Unit = {
      collection.insertOne(ar).toFuture()
      if (ar.level >= Level.ERR) {
        if (LoggerConfig.config.alertEmail)
          emailTargetOp.getList().foreach { emailTargets =>
            val emails = emailTargets.map(_._id)

            AlertEmailSender.sendAlertMail(mailerClient = mailerClient)("警報通知", emails, ar.desc)
          }

        for (token <- sysConfig.getLineChannelToken if token.nonEmpty)
          lineNotify.broadcast(token, ar.desc)
      }
    }

    if (coldPeriod == 0) {
      insertAlarm()
    } else {
      val start = Date.from(Instant.ofEpochMilli(ar.time.getTime).minusSeconds(coldPeriod * 60))
      val end = ar.time

      val countObserver = collection.countDocuments(and(gte("time", start), lt("time", end),
        equal("src", ar.src), equal("level", ar.level), equal("desc", ar.desc)))

      countObserver.subscribe(
        (count: Long) => {
          if (count == 0)
            insertAlarm()
        }, // onNext
        (ex: Throwable) => logger.error("Alarm failed:", ex), // onError
        () => {} // onComplete
      )
    }
  }

  override def log(src: String, level: Int, desc: String, coldPeriod: Int = 30): Unit = {
    val ar = Alarm(DateTime.now(), src, level, desc)
    logFilter(ar, coldPeriod)
  }
}