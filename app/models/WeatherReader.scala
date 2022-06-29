package models

import akka.actor._
import com.github.nscala_time.time.Imports.{DateTime, Period}
import models.ModelHelper.{getHourBetween, getPeriods, waitReadyResult}
import models.mongodb.RecordOp
import play.api._

import java.io.File
import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDateTime, ZoneId}
import java.util.Date
import scala.concurrent.duration.{FiniteDuration, MINUTES, SECONDS}
import scala.concurrent.{Future, blocking}
import scala.io.{Codec, Source}

case class WeatherReaderConfig(enable: Boolean, dir: String)
import scala.concurrent.ExecutionContext.Implicits.global

object WeatherReader {
  def start(configuration: Configuration, actorSystem: ActorSystem,
            sysConfig: SysConfigDB, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB, dataCollectManagerOp: DataCollectManagerOp) = {
    def getConfig: Option[WeatherReaderConfig] = {
      for {config <- configuration.getConfig("weatherReader")
           enable <- config.getBoolean("enable")
           dir <- config.getString("dir")
           }
      yield
        WeatherReaderConfig(enable, dir)
    }

    for (config <- getConfig if config.enable)
      actorSystem.actorOf(props(config, sysConfig, monitorTypeOp, recordOp, dataCollectManagerOp), "weatherReader")
  }

  def props(config: WeatherReaderConfig, sysConfig: SysConfigDB, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB, dataCollectManagerOp: DataCollectManagerOp) =
    Props(classOf[WeatherReader], config, sysConfig, monitorTypeOp, recordOp, dataCollectManagerOp)

  case object ParseReport
}

class WeatherReader(config: WeatherReaderConfig, sysConfig: SysConfigDB,
                    monitorTypeOp: MonitorTypeDB, recordOp: RecordDB, dataCollectManagerOp: DataCollectManagerOp) extends Actor {
  Logger.info(s"WeatherReader start reading: ${config.dir}")

  import WeatherReader._

  val mtList = Seq(MonitorType.WIN_SPEED, MonitorType.WIN_DIRECTION, MonitorType.TEMP, MonitorType.HUMID,
    MonitorType.PRESS, MonitorType.SOLAR, "Photometric", MonitorType.RAIN, "Visible", "Battery")
  var timer: Cancellable = context.system.scheduler.scheduleOnce(FiniteDuration(5, SECONDS), self, ParseReport)

  override def receive: Receive = {
    case ParseReport =>
      Future {
        blocking {
          try {
            fileParser(new File(config.dir))
            timer = context.system.scheduler.scheduleOnce(FiniteDuration(1, MINUTES), self, ParseReport)
          } catch {
            case ex: Throwable =>
              Logger.error("fail to process weather file", ex)
          }
        }
      }
  }

  def fileParser(file: File): Unit = {
    import scala.collection.mutable.ListBuffer
    for (mt <- mtList)
      monitorTypeOp.ensureMeasuring(mt)

    Logger.debug(s"parsing ${file.getAbsolutePath}")
    val skipLines = waitReadyResult(sysConfig.getWeatherSkipLine())

    var processedLine = 0
    val src = Source.fromFile(file)(Codec.UTF8)
    try {
      val lines = src.getLines().drop(4 + skipLines)
      var dataBegin = LocalDateTime.MAX
      var dataEnd = LocalDateTime.MIN
      val docList = ListBuffer.empty[RecordList]
      for (line <- lines if processedLine < 2000) {
        try {
          val token: Array[String] = line.split(",")
          if (token.length < 12) {
            throw new Exception("unexpected file length")
          }
          val dt: LocalDateTime = LocalDateTime.parse(token(0), DateTimeFormatter.ofPattern("\"yyyy-MM-dd HH:mm:ss\""))

          if (dt.isBefore(dataBegin))
            dataBegin = dt

          if (dt.isAfter(dataEnd))
            dataEnd = dt

          val mtRecordOpts: Seq[Option[MtRecord]] =
            for ((mt, idx) <- mtList.zipWithIndex) yield {
              try {
                val value = token(idx + 2).toDouble
                Some(MtRecord(mt, Some(value), MonitorStatus.NormalStat))
              } catch {
                case _: Exception =>
                  None
              }
            }

          docList.append(RecordList(time = Date.from(dt.atZone(ZoneId.systemDefault()).toInstant), monitor = Monitor.SELF_ID,
            mtDataList = mtRecordOpts.flatten))
        } catch {
          case ex: Throwable =>
            Logger.warn("skip unknown line", ex)
        } finally {
          processedLine = processedLine + 1
        }
      }

      if (docList.nonEmpty) {
        Logger.debug(s"update ${docList.head}")
        sysConfig.setWeatherSkipLine(skipLines + processedLine)
        recordOp.upsertManyRecords(recordOp.MinCollection)(docList)

        val start = new DateTime(Date.from(dataBegin.atZone(ZoneId.systemDefault()).toInstant))
        val end = new DateTime(Date.from(dataEnd.atZone(ZoneId.systemDefault()).toInstant))

        for (current <- getHourBetween(start, end))
          dataCollectManagerOp.recalculateHourData(Monitor.SELF_ID, current)(monitorTypeOp.mtvList)
      }
    } finally {
      src.close()
    }
  }

  override def postStop(): Unit = {
    timer.cancel()
    super.postStop()
  }

  case class ParseResult(fileLastModified: Instant, dataStart: Instant, dataEnd: Instant)
}
