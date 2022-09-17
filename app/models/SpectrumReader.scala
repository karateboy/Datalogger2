package models

import akka.actor._
import com.github.nscala_time.time.Imports.{DateTime, Period}
import com.github.tototoshi.csv.CSVReader
import models.ModelHelper.{errorHandler, getPeriods}
import models.SpectrumReader.getLastModified
import models.mongodb.RecordOp
import play.api._

import java.io.File
import java.nio.file.attribute.FileTime
import java.time.format.DateTimeFormatter
import java.time.{Duration, Instant, LocalDateTime, ZoneId}
import java.util.Date
import scala.concurrent.duration.{FiniteDuration, MINUTES, SECONDS}
import scala.concurrent.{Future, blocking}

case class SpectrumReaderConfig(enable: Boolean, dir: String, postfix: String)
import scala.concurrent.ExecutionContext.Implicits.global

object SpectrumReader {
  def start(configuration: Configuration, actorSystem: ActorSystem,
            sysConfig: SysConfigDB, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB, dataCollectManagerOp: DataCollectManagerOp) = {
    def getConfig: Option[SpectrumReaderConfig] = {
      for {config <- configuration.getConfig("spectrumReader")
           enable <- config.getBoolean("enable")
           dir <- config.getString("dir")
           postfix <- config.getString("postfix")
           }
      yield
        SpectrumReaderConfig(enable, dir, postfix)
    }

    for (config <- getConfig if config.enable)
      actorSystem.actorOf(props(config, sysConfig, monitorTypeOp, recordOp, dataCollectManagerOp), "spectrumReader")
  }

  def props(config: SpectrumReaderConfig, sysConfig: SysConfigDB, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB, dataCollectManagerOp: DataCollectManagerOp) =
    Props(classOf[SpectrumReader], config, sysConfig, monitorTypeOp, recordOp, dataCollectManagerOp)

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
      val files = allFileAndDirs.filter(p => !p.isDirectory() && p.getName.endsWith("csv")
        && getLastModified(p).toInstant.isAfter(lastParseTime))
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

class SpectrumReader(config: SpectrumReaderConfig, sysConfig: SysConfigDB,
                     monitorTypeOp: MonitorTypeDB, recordOp: RecordDB, dataCollectManagerOp: DataCollectManagerOp) extends Actor {
  Logger.info(s"SpectrumReader start reading: ${config.dir}")

  import SpectrumReader._

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

  def processInputPath() = {
    for (lastParseTime <- sysConfig.getSpectrumLastParseTime()) {
      val files = listDirs(config.dir, lastParseTime)

      val resultFutureList: Seq[Future[Option[ParseResult]]] =
        for (dir <- files) yield
          csvFileParser(dir)

      val f = Future.sequence(resultFutureList)
      for (resultOptList <- f) {
        val resultList: Seq[ParseResult] = resultOptList.flatten
        val lastModifiedList = resultList.map(_.fileLastModified)
        if (lastModifiedList.nonEmpty) {
          val max = lastModifiedList.max
          sysConfig.setSpectrumLastParseTime(max.plus(Duration.ofSeconds(1)))
          val dataStart = Date.from(resultList.map(_.dataStart).min.atZone(ZoneId.systemDefault()).toInstant)
          val dataEnd = Date.from(resultList.map(_.dataEnd).max.atZone(ZoneId.systemDefault()).toInstant)
          val start = new DateTime(dataStart).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0)
          val end = new DateTime(dataEnd).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0)


          for (current <- getPeriods(start, end, Period.hours(1)))
            dataCollectManagerOp.recalculateHourData(Monitor.activeId, current)(monitorTypeOp.activeMtvList, monitorTypeOp)
        }
      }
      f onFailure errorHandler
      f onComplete({
        case _ =>
          timer = context.system.scheduler.scheduleOnce(FiniteDuration(1, MINUTES), self, ParseReport)
      })
    }
  }

  def csvFileParser(file: File): Future[Option[ParseResult]] = {
    val tokens = file.getName.split("\\.")

    val mtName = s"${tokens(0)}${config.postfix}"
    monitorTypeOp.ensure(mtName)

    val reader = CSVReader.open(file)
    var dataBegin = Instant.MAX
    var dataEnd = Instant.MIN
    val values =
      for (record <- reader.iterator) yield
        try {
          val dt = LocalDateTime.parse(record(0),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
          )
          val value = record(1).toDouble

          Some((dt, value))
        } catch {
          case ex: Throwable =>
            Logger.warn("failed to parse spectrum file", ex)
            None
        }
    val minAvg: Map[LocalDateTime, Double] = values.flatten.toList.groupBy(t => t._1.withSecond(0).withNano(0))
      .map(kv => {
        val avg = kv._2.map(_._2).sum / kv._2.size
        (kv._1, avg)
      })

    val docOpts =
      for (record <- minAvg) yield
        try {
          val dt = record._1.atZone(ZoneId.systemDefault()).toInstant
          val value = record._2
          if (dt.isBefore(dataBegin))
            dataBegin = dt

          if (dt.isAfter(dataEnd))
            dataEnd = dt
          Some(RecordList.factory(time = Date.from(dt), monitor = Monitor.activeId,
            mtDataList = Seq(MtRecord(mtName, Some(value), MonitorStatus.NormalStat))))
        } catch {
          case ex: Throwable =>
            None
        }

    val docs = docOpts.flatten.toList

    reader.close()
    if (docs.nonEmpty) {
      for (ret <- recordOp.upsertManyRecords(recordOp.MinCollection)(docs)) yield
        Some(ParseResult(getLastModified(file).toInstant, dataBegin, dataEnd, mtName))
    } else
      Future {
        None
      }
  }

  override def postStop(): Unit = {
    timer.cancel()
    super.postStop()
  }

  case class ParseResult(fileLastModified: Instant, dataStart: Instant, dataEnd: Instant, mtName: String)
}
