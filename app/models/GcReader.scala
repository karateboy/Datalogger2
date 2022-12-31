package models

import akka.actor._
import play.api._
import play.api.libs.ws.WSClient

import java.io.File
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{FiniteDuration, MINUTES}
import scala.concurrent.{Future, blocking}
import scala.language.implicitConversions
import scala.util.{Failure, Success}

case class GcMonitorConfig(id: String, name: String, lat: Double, lng: Double, path: String)

case class GcReaderConfig(enable: Boolean, monitors: Seq[GcMonitorConfig])

object GcReader {
  var count = 0

  def start(configuration: Configuration, actorSystem: ActorSystem, monitorOp: MonitorDB, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB, WSClient: WSClient, monitorDB: MonitorDB) = {
    def getConfig: Option[GcReaderConfig] = {
      def getMonitorConfig(config: Configuration) = {
        val id = config.getString("id").get
        val name = config.getString("name").get
        val lat = config.getDouble("lat").get
        val lng = config.getDouble("lng").get
        val path = config.getString("path").get
        GcMonitorConfig(id, name, lat, lng, path)
      }

      for {config <- configuration.getConfig("gcReader")
           enable <- config.getBoolean("enable") if enable
           monitorConfigs <- config.getConfigSeq("monitors")
           monitors = monitorConfigs.map(getMonitorConfig)
           }
      yield
        GcReaderConfig(enable, monitors)
    }

    for (config <- getConfig if config.enable) {
      Logger.info(config.toString)
      config.monitors.foreach(config => {
        monitorOp.ensure(Monitor(_id = config.id, desc = config.name, lat = Some(config.lat), lng = Some(config.lng)))
      })
      count = count + 1
      actorSystem.actorOf(props(config, monitorTypeOp, recordOp, WSClient, monitorDB), s"GcReader${count}")
    }
  }

  private def props(config: GcReaderConfig, monitorTypeOp: MonitorTypeDB,
                    recordOp: RecordDB, WSClient: WSClient, monitorDB: MonitorDB) =
    Props(new GcReader(config, monitorTypeOp, recordOp, WSClient, monitorDB))


  case object ParseReport

  import java.nio.file._
  import java.nio.file.attribute.DosFileAttributes

  def listDirs(files_path: String): List[File] = {
    //import java.io.FileFilter
    val path = new java.io.File(files_path)
    if (path.exists() && path.isDirectory) {
      def isArchive(f: File) = {
        val dfa = Files.readAttributes(Paths.get(f.getAbsolutePath), classOf[DosFileAttributes])
        dfa.isArchive
      }

      val allFileAndDirs = new java.io.File(files_path).listFiles().toList
      val dirs = allFileAndDirs.filter(p => p != null && p.isDirectory() && !isArchive(p))
      dirs.filter(p => p.getName.endsWith(".D"))
    } else {
      Logger.warn(s"invalid input path ${files_path}")
      List.empty[File]
    }
  }

  def setArchive(f: File): Unit = {
    import java.nio.file.attribute.DosFileAttributeView
    val path = Paths.get(f.getAbsolutePath)
    val dosView = Files.getFileAttributeView(path, classOf[DosFileAttributeView])
    dosView.setArchive(true)
  }

  def parser(gcMonitorConfig: GcMonitorConfig, reportDir: File)
            (implicit recordOp: RecordDB, wsClient: WSClient, monitorDB: MonitorDB, monitorTypeDB: MonitorTypeDB): Boolean = {
    import com.github.nscala_time.time.Imports._

    import java.nio.charset.StandardCharsets
    import java.nio.file.{Files, Paths}
    import scala.collection.JavaConverters._

    val fileLines =
      Files.readAllLines(Paths.get(reportDir.getAbsolutePath + "/quant.txt"), StandardCharsets.UTF_8).asScala

    val dateTimeOpt = {
      for (line <- fileLines.find(line =>
        line.trim.startsWith("Acq On"))) yield {
        import java.util.Locale
        val pattern = line.split(":", 2)(1).trim()
        DateTime.parse(pattern, DateTimeFormat.forPattern("d MMM YYYY  hh:mm aa").withLocale(Locale.US))
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

    //val recordMap = mutable.Map.empty[String, mutable.Map[DateTime, mutable.Map[String, (Double, String)]]]
    //val timeMap = recordMap.getOrElseUpdate(monitor, mutable.Map.empty[DateTime, mutable.Map[String, (Double, String)]])
    def lineToMtRecord(line: String): Option[MtRecord] =
      try {
        val mtName = line.slice(8, 35).trim
        val value = line.slice(59, 64).trim.toDouble
        Some(MtRecord(mtName, Some(value), MonitorStatus.NormalStat))
      } catch {
        case _: Throwable =>
          None
      }

    val internalValues = getInternalRecordLines(fileLines).flatMap(lineToMtRecord)
    val actualValues = getTargetRecordLists(fileLines).flatMap(lineToMtRecord)


    for (dateTime <- dateTimeOpt) {
      val record = RecordList(dateTime.toDate, internalValues ++ actualValues, gcMonitorConfig.id)
      record.mtDataList.foreach(mtRecord => {
        monitorTypeDB.ensure(mtRecord.mtName)
        recordOp.ensureMonitorType(mtRecord.mtName)
      })

      val f = recordOp.upsertRecord(record)(recordOp.HourCollection)
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

private class GcReader(config: GcReaderConfig, monitorTypeOp: MonitorTypeDB, recordOp: RecordDB, WSClient: WSClient, monitorDB: MonitorDB)
  extends Actor with ActorLogging {
  Logger.info("GcReader start")

  import GcReader._

  override def postStop(): Unit = {
    //timer.cancel()
  }

  override def receive: Receive = handler(Map.empty[String, Int])

  private val MAX_RETRY_COUNT = 60
  self ! ParseReport

  def handler(retryMap: Map[String, Int]): Receive = {
    case ParseReport =>
      def processInputPath(gcMonitorConfig: GcMonitorConfig, parser: (GcMonitorConfig, File) => Boolean): Map[String, Int] = {
        var updatedRetryMap = retryMap
        val dirs = listDirs(gcMonitorConfig.path)
        for (dir <- dirs) yield {
          val absPath = dir.getAbsolutePath
          if (!retryMap.contains(absPath))
            Logger.info(s"Processing $absPath")

          try {
            parser(gcMonitorConfig, dir)
            setArchive(dir)
            Logger.info(s"Handle $absPath successfully. Mark $absPath as archive")
            updatedRetryMap = updatedRetryMap - absPath
          } catch {
            case ex: Throwable =>
              if (updatedRetryMap.contains(absPath)) {
                if (updatedRetryMap(absPath) + 1 <= MAX_RETRY_COUNT) {
                  updatedRetryMap = updatedRetryMap + (absPath -> (updatedRetryMap(absPath) + 1))
                } else {
                  Logger.info(s"$absPath reach max retries. Give up!")
                  Logger.info(s"Mark $absPath as archive")
                  setArchive(dir)
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
              context become handler(processInputPath(gcMonitorConfig, parser))
            }
          }
        }
      } catch {
        case ex: Throwable =>
          Logger.error("process InputPath failed", ex)
      } finally {
        context.system.scheduler.scheduleOnce(FiniteDuration(1, MINUTES), self, ParseReport)
      }
  }
}