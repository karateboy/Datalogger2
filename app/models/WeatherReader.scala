package models

import akka.actor._
import com.github.nscala_time.time.Imports.DateTime
import models.ModelHelper.{getHourBetween, waitReadyResult}
import play.api._

import java.io.File
import java.time.format.{DateTimeFormatter, DateTimeParseException}
import java.time.{Instant, LocalDateTime, ZoneId}
import java.util.Date
import scala.concurrent.duration.{FiniteDuration, MINUTES, SECONDS}
import scala.concurrent.{Future, blocking}
import scala.io.{Codec, Source}

case class WeatherReaderConfig(enable: Boolean, dir: String, model:String, realtimeDir: Option[String])
import scala.concurrent.ExecutionContext.Implicits.global

object WeatherReader {
  val CR800_MODEL = "CR800"
  val CR300_MODEL = "CR300"
  val models = Seq(CR800_MODEL, CR300_MODEL)

  def start(configuration: Configuration, actorSystem: ActorSystem,
            sysConfig: SysConfigDB, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB, dataCollectManagerOp: DataCollectManagerOp) = {
    def getConfig: Option[WeatherReaderConfig] = {
      for {config <- configuration.getConfig("weatherReader")
           enable <- config.getBoolean("enable")
           dir <- config.getString("dir")
           realtimeDir = config.getString("realtime")
           modelOpt = config.getString("model")
           }
      yield
        WeatherReaderConfig(enable, dir, modelOpt.getOrElse(CR800_MODEL), realtimeDir)
    }

    for (config <- getConfig if config.enable)
      actorSystem.actorOf(props(config, sysConfig, monitorTypeOp, recordOp, dataCollectManagerOp), "weatherReader")
  }

  def props(config: WeatherReaderConfig, sysConfig: SysConfigDB, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB, dataCollectManagerOp: DataCollectManagerOp) =
    Props(classOf[WeatherReader], config, sysConfig, monitorTypeOp, recordOp, dataCollectManagerOp)

  case object ParseReport
  case object ParseRealtimeReport

  @volatile var latestRecord = Seq.empty[MtRecord]
}

class WeatherReader(config: WeatherReaderConfig, sysConfig: SysConfigDB,
                    monitorTypeOp: MonitorTypeDB, recordOp: RecordDB, dataCollectManagerOp: DataCollectManagerOp) extends Actor {
  Logger.info(s"WeatherReader start processing ${config.model}: ${config.dir}")

  import WeatherReader._

  val CR800_MT_LIST = Seq(MonitorType.WIN_SPEED, MonitorType.WIN_DIRECTION, MonitorType.TEMP, MonitorType.HUMID,
    MonitorType.PRESS, MonitorType.SOLAR, "Photometric", MonitorType.RAIN, "Visible", "Battery")

  val CR300_MT_LIST = Seq(MonitorType.WIN_SPEED, MonitorType.WIN_DIRECTION, MonitorType.WINSPEED_MAX,
    MonitorType.TEMP, MonitorType.HUMID, MonitorType.RAIN)

  val mtList: Seq[String] = config.model match {
    case CR300_MODEL =>
      CR300_MT_LIST

    case CR800_MODEL =>
      CR800_MT_LIST

    case _ =>
      throw new Exception(s"unknown ${config.model} model")
  }

  for (mt <- mtList)
    recordOp.ensureMonitorType(mt)

  private val realtimeTimer: Option[Cancellable] = for(_ <- config.realtimeDir) yield
    context.system.scheduler.schedule(FiniteDuration(1, SECONDS), FiniteDuration(3, SECONDS), self, ParseRealtimeReport)

  Logger.info(s"WeatherReader: ${config.toString}")
  override def receive: Receive = {
    case ParseReport =>
      Future {
        blocking {
          try {
            fileParser(new File(config.dir))
          } catch {
            case ex: Throwable =>
              Logger.error("fail to process weather file", ex)
          } finally {
            context.system.scheduler.scheduleOnce(FiniteDuration(1, MINUTES), self, ParseReport)
          }
        }
      }
    case ParseRealtimeReport =>
      Future {
        blocking {
          try {
            for(realtimeDir<-config.realtimeDir)
              realtimeFileParser(new File(realtimeDir))
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
      monitorTypeOp.ensure(mt)

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
          if (token.length < mtList.length) {
            throw new Exception("unexpected file length")
          }
          val dt: LocalDateTime = try {
            LocalDateTime.parse(token(0), DateTimeFormatter.ofPattern("\"yyyy-MM-dd HH:mm:ss\""))
          } catch {
            case _: DateTimeParseException =>
              LocalDateTime.parse(token(0), DateTimeFormatter.ofPattern("\"yyyy/MM/dd HH:mm:ss\""))
          }

          if (dt.isBefore(dataBegin))
            dataBegin = dt

          if (dt.isAfter(dataEnd))
            dataEnd = dt

          val mtRecordOpts: Seq[Option[MtRecord]] =
            for ((mt, idx) <- mtList.zipWithIndex) yield {
              try {
                val value = token(idx + 2).toDouble
                val mtCase = monitorTypeOp.map(mt)
                Some(monitorTypeOp.getMinMtRecordByRawValue(mt, Some(value), MonitorStatus.NormalStat)(mtCase.fixedM, mtCase.fixedB))
              } catch {
                case _: Exception =>
                  None
              }
            }

          docList.append(RecordList(time = Date.from(dt.atZone(ZoneId.systemDefault()).toInstant), monitor = Monitor.activeId,
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
          dataCollectManagerOp.recalculateHourData(Monitor.activeId, current)(monitorTypeOp.activeMtvList, monitorTypeOp)
      }
    } finally {
      src.close()
    }
  }

  def realtimeFileParser(file: File): Unit = {
    for (mt <- mtList)
      monitorTypeOp.ensure(mt)

    Logger.debug(s"parsing ${file.getAbsolutePath}")

    val src = Source.fromFile(file)(Codec.UTF8)
    try {
      val lines = src.getLines().drop(4)
      for (line <- lines) {
        try {
          val token: Array[String] = line.split(",")
          if (token.length < mtList.length) {
            throw new Exception("unexpected file length")
          }

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

          WeatherReader.latestRecord = mtRecordOpts.flatten
        } catch {
          case ex: Throwable =>
            Logger.warn("skip unknown line", ex)
        } finally {
        }
      }

    } finally {
      src.close()
    }
  }

  override def postStop(): Unit = {
    for(realtimeTM<-realtimeTimer)
      realtimeTM.cancel()

    super.postStop()
  }

  case class ParseResult(fileLastModified: Instant, dataStart: Instant, dataEnd: Instant)
}
