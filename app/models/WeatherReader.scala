package models

import akka.actor._
import com.github.nscala_time.time.Imports.{DateTime, Period}
import models.ModelHelper.{errorHandler, getPeriods}
import play.api._

import java.io.File
import java.nio.file.attribute.FileTime
import java.time.format.DateTimeFormatter
import java.time.{Duration, Instant, LocalDateTime, ZoneId}
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

  def getLastModified(f: File): FileTime = {
    import java.nio.file._
    import java.nio.file.attribute.DosFileAttributes

    val dfa = Files.readAttributes(Paths.get(f.getAbsolutePath), classOf[DosFileAttributes])
    dfa.lastModifiedTime()
  }

  def listDirs(files_path: String, lastParseTime: Instant): List[File] = {
    val path = new java.io.File(files_path)
    if (path.exists() && path.isDirectory()) {
      val allFileAndDirs = new java.io.File(files_path).listFiles().toList.filter(f => {
        f != null
      })

      val dirs = allFileAndDirs.filter(p => p.isDirectory())
      val files = allFileAndDirs.filter(p => !p.isDirectory() && getLastModified(p).toInstant.isAfter(lastParseTime))
      if (dirs.isEmpty)
        files
      else {
        val deepDir = dirs flatMap (dir => listDirs(dir.getAbsolutePath, lastParseTime))
        files ++ deepDir
      }
    } else {
      Logger.warn(s"invalid input path ${files_path}")
      List.empty[File]
    }
  }

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
          try{
            processInputPath()
          }catch{
            case ex:Throwable =>
              Logger.error("fail to process spectrum folder", ex)
          }
        }
      }
  }

  def processInputPath(): Unit = {
    for (lastParseTime <- sysConfig.getWeatherLastParseTime()) {
      val files = listDirs(config.dir, lastParseTime)

      val resultFutureList: Seq[Future[Option[ParseResult]]] =
        for (dir <- files) yield
          fileParser(dir)

      val f = Future.sequence(resultFutureList)
      for (resultOptList <- f) {
        val resultList: Seq[ParseResult] = resultOptList.flatten
        val lastModifiedList = resultList.map(_.fileLastModified)
        if (lastModifiedList.nonEmpty) {
          val max = lastModifiedList.max
          sysConfig.setWeatherLastParseTime(max.plus(Duration.ofSeconds(1)))
          val dataStart = Date.from(resultList.map(_.dataStart).min.atZone(ZoneId.systemDefault()).toInstant)
          val dataEnd = Date.from(resultList.map(_.dataEnd).max.atZone(ZoneId.systemDefault()).toInstant)
          val start = new DateTime(dataStart).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0)
          val end = new DateTime(dataEnd).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0)


          for (current <- getPeriods(start, end, Period.hours(1)))
            dataCollectManagerOp.recalculateHourData(Monitor.SELF_ID, current, false, true)(monitorTypeOp.mtvList)
        }
      }
      f onFailure errorHandler
      f onComplete({
        case _ =>
          timer = context.system.scheduler.scheduleOnce(FiniteDuration(1, MINUTES), self, ParseReport)
      })
    }
  }

  val mtList= Seq(MonitorType.WIN_SPEED, MonitorType.WIN_DIRECTION, MonitorType.TEMP, MonitorType.HUMID,
    MonitorType.PRESS, MonitorType.SOLAR, "Photometric", MonitorType.RAIN, "Visible", "Battery")

  var processedLine = 0
  def fileParser(file: File): Future[Option[ParseResult]] = {
    for(mt<-mtList)
      monitorTypeOp.ensureMonitorType(mt)

    val lines = Source.fromFile(file).getLines().drop(4 + processedLine)
    var dataBegin = Instant.MAX
    var dataEnd = Instant.MIN

    val docOptIterator =
      for (line <- lines) yield
        try {
          val token: Array[String] = line.split(",")
          if(token.length < 12) {
            throw new Exception("unexpected file length")
          }

          val dt = LocalDateTime.parse(token(0), DateTimeFormatter.ofPattern("\"yyyy-MM-dd HH:mm:ss\""))
            .atZone(ZoneId.systemDefault()).toInstant

          if (dt.isBefore(dataBegin))
            dataBegin = dt

          if (dt.isAfter(dataEnd))
            dataEnd = dt
          val mtRecords: Seq[MtRecord] =
          for((mt, idx) <- mtList.zipWithIndex) yield {
            val value = token(idx + 2).toDouble
            MtRecord(mt, value, MonitorStatus.NormalStat)
          }
          processedLine = processedLine + 1
          Some(RecordList(time = Date.from(dt), monitor = Monitor.SELF_ID,
            mtDataList = mtRecords))
        } catch {
          case ex: Throwable =>
            Logger.warn("failed to parse weather file", ex)
            None
        }


    val docs = docOptIterator.flatten.toList
    if (docs.nonEmpty) {
      for (_ <- recordOp.upsertManyRecords2(recordOp.MinCollection)(docs)) yield
        Some(ParseResult(getLastModified(file).toInstant, dataBegin, dataEnd))
    } else
      Future {
        None
      }
  }

  override def postStop(): Unit = {
    timer.cancel()
    super.postStop()
  }

  case class ParseResult(fileLastModified: Instant, dataStart: Instant, dataEnd: Instant)
}
