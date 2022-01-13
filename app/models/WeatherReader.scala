package models

import akka.actor._
import com.github.nscala_time.time.Imports.{DateTime, Period}
import models.ModelHelper.getPeriods
import play.api._

import java.io.File
import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDateTime, ZoneId}
import java.util.Date
import scala.concurrent.duration.{FiniteDuration, MINUTES, SECONDS}
import scala.concurrent.{Future, blocking}
import scala.io.Source

case class WeatherReaderConfig(enable: Boolean, dir: String)
import scala.concurrent.ExecutionContext.Implicits.global

object WeatherReader {
  def start(configuration: Configuration, actorSystem: ActorSystem,
            sysConfig: SysConfig, monitorTypeOp: MonitorTypeOp,
            recordOp: RecordOp, dataCollectManagerOp: DataCollectManagerOp) = {
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

  def props(config: WeatherReaderConfig, sysConfig: SysConfig, monitorTypeOp: MonitorTypeOp,
            recordOp: RecordOp, dataCollectManagerOp: DataCollectManagerOp) =
    Props(classOf[WeatherReader], config, sysConfig, monitorTypeOp, recordOp, dataCollectManagerOp)

  case object ParseReport
}

class WeatherReader(config: WeatherReaderConfig, sysConfig: SysConfig,
                    monitorTypeOp: MonitorTypeOp, recordOp: RecordOp, dataCollectManagerOp: DataCollectManagerOp) extends Actor {
  Logger.info(s"WeatherReader start reading: ${config.dir}")

  import WeatherReader._

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
              Logger.error("fail to process spectrum file", ex)
          }
        }
      }
  }

  val mtList = Seq(MonitorType.WIN_SPEED, MonitorType.WIN_DIRECTION, MonitorType.TEMP, MonitorType.HUMID,
    MonitorType.PRESS, MonitorType.SOLAR, "Photometric", MonitorType.RAIN, "Visible", "Battery")


  def fileParser(file: File): Unit = {
    import scala.collection.mutable.ListBuffer
    for (mt <- mtList)
      monitorTypeOp.ensureMonitorType(mt)

    for (skipLines <- sysConfig.getWeatherSkipLine()) {
      var processedLine = 0
      val lines = Source.fromFile(file).getLines().drop(4 + skipLines)
      var dataBegin = LocalDateTime.MAX
      var dataEnd = LocalDateTime.MIN
      val docList = ListBuffer.empty[RecordList]
      for (line <- lines if processedLine < 500) {
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
                Some(MtRecord(mt, value, MonitorStatus.NormalStat))
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
            None
        } finally {
          processedLine = processedLine + 1
        }
      }

      sysConfig.setWeatherSkipLine(skipLines + processedLine)

      if (docList.nonEmpty) {
        recordOp.upsertManyRecords2(recordOp.MinCollection)(docList)

        val start = new DateTime(dataBegin).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0)
        val end = new DateTime(dataEnd).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0)

        for (current <- getPeriods(start, end, Period.hours(1)))
          dataCollectManagerOp.recalculateHourData(Monitor.SELF_ID, current, false, true)(monitorTypeOp.mtvList)
      }
    }
  }

  override def postStop(): Unit = {
    timer.cancel()
    super.postStop()
  }

  case class ParseResult(fileLastModified: Instant, dataStart: Instant, dataEnd: Instant)
}
