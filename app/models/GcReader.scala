package models

import akka.actor._
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.DirectoryFileFilter
import play.api._
import play.api.libs.ws.WSClient

import java.io.File
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{FiniteDuration, MINUTES}
import scala.concurrent.{Future, blocking}
import scala.language.implicitConversions
import scala.util.{Failure, Success}

case class GcMonitorConfig(id: String,
                           name: String,
                           lat: Double,
                           lng: Double,
                           path: String,
                           fileName: String,
                           mtPostfix: Option[String],
                           mtAnnotation: Option[String]
                          )

case class GcReaderConfig(enable: Boolean, monitors: Seq[GcMonitorConfig])

object GcReader {
  var count = 0

  def start(configuration: Configuration, actorSystem: ActorSystem, monitorOp: MonitorDB, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB, WSClient: WSClient, monitorDB: MonitorDB, environment: Environment): Option[ActorRef] = {
    def getConfig: Option[GcReaderConfig] = {
      def getMonitorConfig(config: Configuration) = {
        val id = config.getString("id").get
        val name = config.getString("name").get
        val lat = config.getDouble("lat").get
        val lng = config.getDouble("lng").get
        val path = config.getString("path").get
        val fileName = config.getString("fileName").get
        val mtPostfix = config.getString("mtPostfix")
        val mtAnnotation = config.getString("mtAnnotation")
        GcMonitorConfig(id, name, lat, lng, path, fileName = fileName, mtPostfix = mtPostfix, mtAnnotation = mtAnnotation)
      }

      for {config <- configuration.getConfig("gcReader")
           enable <- config.getBoolean("enable") if enable
           monitorConfigs <- config.getConfigSeq("monitors")
           monitors = monitorConfigs.map(getMonitorConfig)
           }
      yield
        GcReaderConfig(enable, monitors)
    }

    for (config <- getConfig if config.enable) yield {
      Logger.info(config.toString)
      config.monitors.foreach(config => {
        monitorOp.ensure(Monitor(_id = config.id, desc = config.name, lat = Some(config.lat), lng = Some(config.lng)))
      })
      count = count + 1
      actorSystem.actorOf(props(config, monitorTypeOp, recordOp, WSClient, monitorDB, environment), s"GcReader${count}")
    }
  }

  private def props(config: GcReaderConfig, monitorTypeOp: MonitorTypeDB,
                    recordOp: RecordDB, WSClient: WSClient, monitorDB: MonitorDB, environment: Environment) =
    Props(new GcReader(config, monitorTypeOp, recordOp, WSClient, monitorDB, environment))


  case object ParseReport

  def listDirs(files_path: String): List[File] = {
    //import java.io.FileFilter
    val path = new java.io.File(files_path)
    if (path.exists() && path.isDirectory) {
      import scala.collection.JavaConverters._
      val allDirs = FileUtils.listFilesAndDirs(path, DirectoryFileFilter.DIRECTORY, DirectoryFileFilter.DIRECTORY).asScala.toList
      val dirs = allDirs.filter(p => p != null && p.isDirectory)
      dirs.filter(p => p.getName.endsWith(".D"))
    } else {
      Logger.warn(s"invalid input path ${files_path}")
      List.empty[File]
    }
  }

  def parser(gcMonitorConfig: GcMonitorConfig, reportDir: File)
            (implicit recordOp: RecordDB, wsClient: WSClient, monitorDB: MonitorDB, monitorTypeDB: MonitorTypeDB): Boolean = {
    import com.github.nscala_time.time.Imports._

    import java.nio.charset.StandardCharsets
    import java.nio.file.{Files, Paths}
    import scala.collection.JavaConverters._

    val fileLines =
      Files.readAllLines(Paths.get(reportDir.getAbsolutePath, gcMonitorConfig.fileName), StandardCharsets.UTF_8).asScala

    val dateTimeOpt = {
      for (line <- fileLines.find(line =>
        line.trim.startsWith("Acq On"))) yield {
        import java.util.Locale
        val pattern = line.split(":", 2)(1).trim()
        val dtFormat =
          if (pattern.equalsIgnoreCase("AM") || pattern.equalsIgnoreCase("PM"))
            DateTimeFormat.forPattern("d MMM YYYY  hh:mm aa").withLocale(Locale.US)
          else
            DateTimeFormat.forPattern("d MMM YYYY  HH:mm").withLocale(Locale.US)

        DateTime.parse(pattern, dtFormat)
          .minusHours(1)
          .withMinuteOfHour(0)
          .withSecondOfMinute(0)
          .withMillisOfSecond(0)
      }
    }

    def getInternalRecordLines(lines: Seq[String]): Seq[String] = {
      val head = lines.dropWhile(!_.trim.startsWith("Internal Standards")).drop(1)
      head.takeWhile(_.trim.nonEmpty)
    }

    def getTargetRecordLists(lines: Seq[String]): Seq[String] = {
      val head = lines.dropWhile(!_.trim.startsWith("Target Compounds")).drop(1)
      head.takeWhile(!_.trim.startsWith("-------------"))
    }

    val mtPostfix = gcMonitorConfig.mtPostfix.getOrElse("")

    def lineToMtRecord(line: String): Option[MtRecord] =
      try {
        val mtName = s"${line.slice(8, 35).trim}$mtPostfix"
        val value = line.slice(59, 64).trim.toDouble
        val status = if (reportDir.getName.startsWith("B")) {
          MonitorStatus.CalibratedStat
        } else if (reportDir.getName.startsWith("Q")) {
          MonitorStatus.CalibrationSampleStat
        } else
          MonitorStatus.NormalStat

        Some(MtRecord(mtName, Some(value), status))
      } catch {
        case _: Throwable =>
          None
      }

    val internalValues = getInternalRecordLines(fileLines).flatMap(lineToMtRecord)
    val actualValues = getTargetRecordLists(fileLines).flatMap(lineToMtRecord)


    for (dateTime <- dateTimeOpt) {
      val record = RecordList.factory(dateTime.toDate, internalValues ++ actualValues, gcMonitorConfig.id)
      record.mtDataList.foreach(mtRecord => {

        if (mtPostfix.nonEmpty &&
          monitorTypeDB.map.contains(mtRecord.mtName.dropRight(mtPostfix.length))) {

          val srcMtCase = monitorTypeDB.map(mtRecord.mtName.dropRight(mtPostfix.length))
          val mtCase = srcMtCase.copy(_id = mtRecord.mtName, desp = srcMtCase.desp + gcMonitorConfig.mtAnnotation.getOrElse(""))
          monitorTypeDB.ensure(mtCase)
          recordOp.ensureMonitorType(mtRecord.mtName)
        } else {
          monitorTypeDB.ensure(mtRecord.mtName)
          recordOp.ensureMonitorType(mtRecord.mtName)
        }

      })

      val f = recordOp.upsertRecord(recordOp.HourCollection)(record)
      f onComplete {
        case Success(_) =>
          // Upload
          Logger.info(s"upload GC record $dateTime")
          Uploader.upload(wsClient)(record, monitorDB.map(gcMonitorConfig.id))
        case Failure(exception) =>
          Logger.error("failed", exception)
      }
    }

    true
  } //End of process report.txt
}

private class GcReader(config: GcReaderConfig, monitorTypeOp: MonitorTypeDB, recordOp: RecordDB, WSClient: WSClient, monitorDB: MonitorDB, environment: play.api.Environment)
  extends Actor with ActorLogging {
  Logger.info("GcReader start")

  import GcReader._

  override def postStop(): Unit = {
    //timer.cancel()
  }

  override def receive: Receive = handler(Map.empty[String, Int], mutable.Set.empty[String])

  private val MAX_RETRY_COUNT = 120
  self ! ParseReport

  import ReaderHelper._

  private val parsedFileRoot = environment.rootPath.getAbsolutePath
  private val parsedFile = "parsed.txt"

  def handler(retryMap: Map[String, Int], parsedFileSet: mutable.Set[String]): Receive = {
    case ParseReport =>
      def processInputPath(gcMonitorConfig: GcMonitorConfig, parser: (GcMonitorConfig, File) => Boolean): Map[String, Int] = {
        var updatedRetryMap = retryMap
        if (parsedFileSet.isEmpty) {
          val parsedFileList = getParsedFileList(parsedFileRoot, parsedFile)
          parsedFileSet ++= parsedFileList
        }

        val dirs = listDirs(gcMonitorConfig.path)
        for (dir <- dirs) yield {
          val absPath = dir.getAbsolutePath
          if (!retryMap.contains(absPath))
            Logger.info(s"Processing $absPath")

          try {
            if (parsedFileSet.contains(absPath)) {
              Logger.debug(s"$absPath already parsed. Skip")
              updatedRetryMap = updatedRetryMap - absPath
              return updatedRetryMap
            }

            parser(gcMonitorConfig, dir)
            Logger.info(s"Handle $absPath successfully. Mark $absPath as archive")
            parsedFileSet += absPath
            appendToParsedFileList(parsedFileRoot, absPath, parsedFile)
            updatedRetryMap = updatedRetryMap - absPath
          } catch {
            case ex: Throwable =>
              if (updatedRetryMap.contains(absPath)) {
                if (updatedRetryMap(absPath) + 1 <= MAX_RETRY_COUNT) {
                  updatedRetryMap = updatedRetryMap + (absPath -> (updatedRetryMap(absPath) + 1))
                } else {
                  Logger.info(s"$absPath reach max retries. Give up!")
                  Logger.info(s"Mark $absPath as archive")
                  parsedFileSet += absPath
                  appendToParsedFileList(parsedFileRoot, absPath, parsedFile)
                  updatedRetryMap = updatedRetryMap - absPath
                }
              } else
                updatedRetryMap = updatedRetryMap + (absPath -> 1)
          }
        }
        updatedRetryMap
      }

      implicit val implicitRecordDB: RecordDB = recordOp
      implicit val implicitWsClient: WSClient = WSClient
      implicit val implicitMonitorDB: MonitorDB = monitorDB
      implicit val implicitMonitorTypeDB: MonitorTypeDB = monitorTypeOp
      try {
        for (gcMonitorConfig <- config.monitors) {
          Future {
            blocking {
              context become handler(processInputPath(gcMonitorConfig, parser), parsedFileSet)
            }
          }
        }
      } catch {
        case ex: Throwable =>
          Logger.error("process InputPath failed", ex)
      } finally {
        context.system.scheduler.scheduleOnce(FiniteDuration(1, MINUTES), self, ParseReport)
      }
    case ReaderReset =>
      Logger.info("ReaderReset")
      removeParsedFileList(parsedFileRoot, parsedFile)
      context become handler(Map.empty[String, Int], mutable.Set.empty[String])
    // wait for the next 1 minute ParseReport event
  }
}