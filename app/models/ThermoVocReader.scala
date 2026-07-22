package models

import akka.actor._
import com.github.nscala_time.time.Imports._
import models.ForwardManager.ForwardHourRecord
import play.api._

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.concurrent.duration.{FiniteDuration, MINUTES}
import scala.concurrent.{Future, blocking}
import scala.jdk.CollectionConverters.asScalaBufferConverter
import scala.util.{Failure, Success}

object ThermoVocReader {
  val logger: Logger = Logger(getClass)
  var count = 0

  def start(configuration: Configuration, actorSystem: ActorSystem, monitorOp: MonitorDB, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB, dataCollectManager: ActorRef): Option[ActorRef] = {
    def getConfig: Option[VocReaderConfig] = {
      def getMonitorConfig(config: Configuration) = {
        val id = config.get[String]("id")
        val name = config.get[String]("name")
        val lat = config.get[Double]("lat")
        val lng = config.get[Double]("lng")
        val path = config.get[String]("path")
        VocMonitorConfig(id, name, lat, lng, path)
      }

      for {config <- configuration.getOptional[Configuration]("ThermoVocReader")
           enable <- config.getOptional[Boolean]("enable") if enable
           monitorConfigs <- config.getOptional[Seq[Configuration]]("monitors")
           monitors = monitorConfigs.map(getMonitorConfig)
           }
      yield
        VocReaderConfig(enable, monitors)
    }

    for (config <- getConfig if config.enable) yield {
      logger.info(config.toString)
      config.monitors.foreach(config => {
        val m = Monitor(_id = config.id, desc = config.name, lat = Some(config.lat), lng = Some(config.lng))
        monitorOp.upsertMonitor(m)
      })
      count = count + 1
      actorSystem.actorOf(props(config, monitorTypeOp, recordOp, dataCollectManager), s"ThermoVocReader${count}")
    }
  }

  def props(config: VocReaderConfig, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB, dataCollectManager: ActorRef): Props =
    Props(new ThermoVocReader(config, monitorTypeOp, recordOp, dataCollectManager))


  private def getFileDateTime(fileName: String, year: Int, month: Int): Option[DateTime] = {
    val dayHour = fileName.takeWhile { x => x != '.' }.dropWhile { x => !x.isDigit }
    if (dayHour.forall { x => x.isDigit }) {
      val day = dayHour.take(2).toInt
      val hour = dayHour.drop(2).toInt - 1
      val localDate = new LocalDate(year, month, day)
      val localTime = new LocalTime(hour, 0)
      Some(localDate.toDateTime(localTime))
    } else
      None
  }

  private case object ReadFile

}

class ThermoVocReader(config: VocReaderConfig,
                      monitorTypeOp: MonitorTypeDB,
                      recordOp: RecordDB,
                      dataCollectManager: ActorRef)
  extends Actor with ActorLogging {

  log.info("ThermoVocReader start")

  import DataCollectManager._
  import ReaderHelper._
  import ThermoVocReader._
  import context.dispatcher

  def receive: Receive = handler(mutable.Map.empty[String, mutable.Set[String]], None)

  self ! ReadFile

  def archivePath(monitorId: String): Path = Paths.get(s"ThermoVoc_${monitorId}.txt")

  def handler(parsedMap: mutable.Map[String, mutable.Set[String]], timerOpt: Option[Cancellable]): Receive = {
    case ReadFile =>
      Future {
        blocking {

          for (monitorConfig <- config.monitors) {
            val parsedFileSet = parsedMap.getOrElseUpdate(monitorConfig.path, mutable.Set.empty[String])
            if (parsedFileSet.isEmpty)
              parsedFileSet ++= getParsedFileList(archivePath(monitorConfig.id))

            parseMonitor(monitorConfig)(parsedFileSet)
          }
          val nextTimer = context.system.scheduler.scheduleOnce(FiniteDuration(5, MINUTES), self, ReadFile)
          context become handler(parsedMap, Some(nextTimer))
        }
      }

    case ReaderReset =>
      for (timer <- timerOpt)
        timer.cancel()

      for (monitorConfig <- config.monitors)
        removeParsedFileList(monitorConfig.path)

      context become handler(mutable.Map.empty[String, mutable.Set[String]], None)
      self ! ReadFile
  }

  private def parseMonitor(monitorConfig: VocMonitorConfig)(parsedFileList: mutable.Set[String]): Unit = {
    parseAllFiles(monitorConfig)(parsedFileList)
  }

  private def parseAllFiles(monitorConfig: VocMonitorConfig, ignoreParsed: Boolean = false)(parsedFileList: mutable.Set[String]): Unit = {
    val dir = Paths.get(monitorConfig.path)

    try {
      val stream = Files.list(dir)
      try stream.filter(path => Files.isRegularFile(path))
        .forEach(path => {
          parser(monitorConfig.id, path)
          parsedFileList.add(path.toAbsolutePath.toString)
          appendToParsedFileList(path.toAbsolutePath.toString, archivePath(monitorConfig.id))
        })
      finally if (stream != null)
        stream.close()
    } catch {
      case ex: Throwable =>
        log.error(ex, "Failed to parseAllFiles")
    }
  }

  private def parser(monitorId: String, path: Path): Unit = {
    try {
      val filename = path.getFileName.toString
      val status = if (filename.contains("QC"))
        MonitorStatus.SpanCalibrationStat
      else if (filename.contains("BK"))
        MonitorStatus.ZeroCalibrationStat
      else
        MonitorStatus.NormalStat

      val lines = Files.readAllLines(path, StandardCharsets.UTF_8).asScala
      val filenameTime =
          DateTime.parse(filename.take(16), DateTimeFormat.forPattern("YYYY_MM_dd HH_mm"))

      val mtDataList = mutable.ListBuffer.empty[MtRecord]
      lines.drop(5).filter(_.exists(_.isDigit)) //Only process line with digit
        .foreach(line => {
          try {
            val pattern = line.split("\\s")
            val mtName = pattern(0).trim
            val value = pattern(2).toDouble
            monitorTypeOp.ensureRangeType(mtName, recordOp)
            mtDataList.append(MtRecord(mtName, Some(value), status))
          } catch {
            case _: Throwable=>
              //skip buggy line
          }
        })

      val dateTime = filenameTime.withMinute(0)
      val f = recordOp.upsertRecordChecked(recordOp.HourCollection)(RecordList.factory(dateTime.toDate, mtDataList, monitorId))
      f onComplete {
        case Success(_) =>
          dataCollectManager ! ForwardHourRecord(dateTime, dateTime.plusHours(1))

        case Failure(ex) =>
          throw ex
      }
    } catch {
      case ex: Throwable =>
        log.error(ex, "parser error")
    }
  }

  override def postStop(): Unit = {
    log.info("ThermoVocReader stopped")
  }
}