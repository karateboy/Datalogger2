package models

import akka.actor._
import models.DataCollectManager.ReaderReset
import models.ModelHelper.waitReadyResult
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.DirectoryFileFilter
import play.api._
import play.api.libs.ws.WSClient

import java.io.File
import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDateTime, ZoneId}
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
                           mtAnnotation: Option[String],
                           backupPath: Option[String]
                          )

case class GcReaderConfig(enable: Boolean, monitors: Seq[GcMonitorConfig])

object GcReader {
  var count = 0
  val logger: Logger = Logger(this.getClass)
  def start(configuration: Configuration, actorSystem: ActorSystem, monitorOp: MonitorDB, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB, WSClient: WSClient, monitorDB: MonitorDB, alarmDB: AlarmDB,
            sysConfigDB: SysConfigDB, ylUploaderConfig: YlUploaderConfig): Option[ActorRef] = {
    def getConfig: Option[GcReaderConfig] = {
      def getMonitorConfig(config: Configuration) = {
        val id = config.get[String]("id")
        val name = config.get[String]("name")
        val lat = config.get[Double]("lat")
        val lng = config.get[Double]("lng")
        val path = config.get[String]("path")
        val fileName = config.get[String]("fileName")
        val mtPostfix = config.getOptional[String]("mtPostfix")
        val mtAnnotation = config.getOptional[String]("mtAnnotation")
        val backupPath = config.getOptional[String]("backupPath")
        GcMonitorConfig(id, name, lat, lng, path, fileName = fileName,
          mtPostfix = mtPostfix, mtAnnotation = mtAnnotation, backupPath = backupPath)
      }

      for {config <- configuration.getOptional[Configuration]("gcReader")
           enable <- config.getOptional[Boolean]("enable") if enable
           monitorConfigs <- config.getOptional[Seq[Configuration]]("monitors")
           monitors = monitorConfigs.map(getMonitorConfig)
           }
      yield
        GcReaderConfig(enable, monitors)
    }

    for (config <- getConfig if config.enable) yield {
      logger.info(config.toString)
      config.monitors.foreach(config => {
        monitorOp.ensure(Monitor(_id = config.id, desc = config.name, lat = Some(config.lat), lng = Some(config.lng)))
      })
      count = count + 1
      actorSystem.actorOf(props(config, monitorTypeOp, recordOp, WSClient, monitorDB,
        alarmDB = alarmDB, sysConfigDB = sysConfigDB, ylUploaderConfig = ylUploaderConfig),
        s"GcReader$count")
    }
  }

  private def props(config: GcReaderConfig, monitorTypeOp: MonitorTypeDB,
                    recordOp: RecordDB, WSClient: WSClient, monitorDB: MonitorDB, alarmDB: AlarmDB,
                    sysConfigDB: SysConfigDB, ylUploaderConfig: YlUploaderConfig) =
    Props(new GcReader(config, monitorTypeOp, recordOp, WSClient, monitorDB, alarmDB, sysConfigDB, ylUploaderConfig))


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
      logger.warn(s"invalid input path $files_path")
      List.empty[File]
    }
  }

  @volatile var vocMonitorTypes = Set.empty[String]
  @volatile var vocAuditMonitorTypes = Set.empty[String]

  def parser(gcMonitorConfig: GcMonitorConfig, reportDir: File)
            (implicit recordOp: RecordDB, wsClient: WSClient,
             monitorDB: MonitorDB, monitorTypeDB: MonitorTypeDB, sysConfigDB: SysConfigDB,
             ylUploaderConfig: YlUploaderConfig): Boolean = {
    import com.github.nscala_time.time.Imports._

    import java.nio.charset.StandardCharsets
    import java.nio.file.{Files, Paths}
    import scala.collection.JavaConverters._

    val f1 =
      if(vocMonitorTypes.isEmpty) {
        for (monitorTypes <- sysConfigDB.getVocMonitorTypes) yield {
          vocMonitorTypes = monitorTypes.toSet
        }
      } else
        Future.successful(())

    val f2 =
      if(vocAuditMonitorTypes.isEmpty) {
        for (monitorTypes <- sysConfigDB.getVocAuditMonitorTypes) yield {
          vocAuditMonitorTypes = monitorTypes.toSet
        }
      } else
        Future.successful(())

    waitReadyResult(Future.sequence(Seq(f1, f2)))

    val fileLines =
      Files.readAllLines(Paths.get(reportDir.getAbsolutePath, gcMonitorConfig.fileName), StandardCharsets.UTF_8).asScala

    val dateTimeOpt = {
      for (line <- fileLines.find(line =>
        line.trim.startsWith("Acq On"))) yield {
        import java.util.Locale
        val pattern = line.split(":", 2)(1).trim()
        val dtFormat =
          if (pattern.endsWith("am") || pattern.endsWith("pm"))
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
      var needUpdateVocMonitorTypes = false
      var needUpdateVocAuditMonitorTypes = false
      val record = RecordList.factory(dateTime.toDate, internalValues ++ actualValues, gcMonitorConfig.id)
      record.mtDataList.foreach(mtRecord => {

        if (mtPostfix.nonEmpty &&
          monitorTypeDB.map.contains(mtRecord.mtName.dropRight(mtPostfix.length))) {

          val srcMtCase = monitorTypeDB.map(mtRecord.mtName.dropRight(mtPostfix.length))
          val mtCase = srcMtCase.copy(_id = mtRecord.mtName, desp = srcMtCase.desp + gcMonitorConfig.mtAnnotation.getOrElse(""))
          monitorTypeDB.ensure(mtCase)
          recordOp.ensureMonitorType(mtRecord.mtName)
          if(!vocAuditMonitorTypes.contains(mtRecord.mtName)) {
            needUpdateVocAuditMonitorTypes = true
            vocAuditMonitorTypes = vocAuditMonitorTypes + mtRecord.mtName
          }
        } else {
          monitorTypeDB.ensure(mtRecord.mtName)
          recordOp.ensureMonitorType(mtRecord.mtName)
          if(!vocMonitorTypes.contains(mtRecord.mtName)) {
            needUpdateVocMonitorTypes = true
            vocMonitorTypes = vocMonitorTypes + mtRecord.mtName
          }
        }
      })

      if(needUpdateVocMonitorTypes)
        sysConfigDB.setVocMonitorTypes(vocMonitorTypes.toList)

      if(needUpdateVocAuditMonitorTypes)
        sysConfigDB.setVocAuditMonitorTypes(vocAuditMonitorTypes.toList)

      val f = recordOp.upsertRecord(recordOp.HourCollection)(record)
      f onComplete {
        case Success(_) =>
          // Upload
          logger.info(s"upload GC record $dateTime")
          YlUploader.upload(wsClient)(record, monitorDB.map(gcMonitorConfig.id), ylUploaderConfig)
        case Failure(exception) =>
          logger.error("failed", exception)
      }
    }

    true
  } //End of process report.txt
}

private class GcReader(config: GcReaderConfig, monitorTypeOp: MonitorTypeDB, recordOp: RecordDB, WSClient: WSClient,
                       monitorDB: MonitorDB, alarmDB: AlarmDB, sysConfigDB: SysConfigDB,
                       ylUploaderConfig: YlUploaderConfig)
  extends Actor with ActorLogging {
  val logger: Logger = Logger(this.getClass)
  logger.info("GcReader start")

  import GcReader._

  override def postStop(): Unit = {
    //timer.cancel()
  }

  override def receive: Receive = handler(Map.empty[String, Int], Instant.now())

  private val MAX_RETRY_COUNT = 120
  self ! ParseReport

  import ReaderHelper._

  def handler(retryMap: Map[String, Int], lastReceiveTime:Instant): Receive = {
    case ParseReport =>
      var receiveTime = lastReceiveTime
      def processInputPath(gcMonitorConfig: GcMonitorConfig, parser: (GcMonitorConfig, File) => Boolean): Map[String, Int] = {
        var updatedRetryMap = retryMap

        val dirs = listDirs(gcMonitorConfig.path)
        for (dir <- dirs) yield {
          val absPath = dir.getAbsolutePath
          if (!retryMap.contains(absPath))
            logger.info(s"Processing $absPath")

          def moveDir(): Unit =
            try {
              FileUtils.moveToDirectory(dir, new File(gcMonitorConfig.backupPath.getOrElse("C:\\GC\\backup")), true)
            } catch {
              case ex: Throwable =>
                logger.error(s"Failed to move ${dir.getAbsolutePath} to backup folder ${gcMonitorConfig.backupPath.getOrElse("C:\\GC\\backup")}", ex)
            }

          def deleteDir(): Unit =
            try {
              FileUtils.deleteDirectory(dir)
            } catch {
              case ex: Throwable =>
                logger.error(s"Failed to delete ${dir.getAbsolutePath}", ex)
            }

          try {
            parser(gcMonitorConfig, dir)
            logger.info(s"Handle $absPath successfully.")
            receiveTime = Instant.now()
            //moveDir()
            deleteDir()
            updatedRetryMap = updatedRetryMap - absPath
          } catch {
            case ex: Throwable =>
              if (updatedRetryMap.contains(absPath)) {
                if (updatedRetryMap(absPath) + 1 <= MAX_RETRY_COUNT) {
                  updatedRetryMap = updatedRetryMap + (absPath -> (updatedRetryMap(absPath) + 1))
                } else {
                  logger.info(s"$absPath reach max retries. Give up! $ex")
                  moveDir()
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
      implicit val implicitSysConfigDB: SysConfigDB = sysConfigDB
      implicit val implicitYlUploaderConfig: YlUploaderConfig = ylUploaderConfig
      try {
        for (gcMonitorConfig <- config.monitors) {
          Future {
            blocking {
              context become handler(processInputPath(gcMonitorConfig, parser), receiveTime)
              if(receiveTime.plusSeconds(90*60).isBefore(Instant.now())) {
                val localDateTime = LocalDateTime.ofInstant(receiveTime, ZoneId.systemDefault())
                alarmDB.log(alarmDB.src(), Alarm.Level.ERR, s"未收到quant.txt警報，最後收到檔案時間為 ${localDateTime.format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))}")
              }
            }
          }
        }
      } catch {
        case ex: Throwable =>
          logger.error("process InputPath failed", ex)
      } finally {
        context.system.scheduler.scheduleOnce(FiniteDuration(1, MINUTES), self, ParseReport)
      }
    case ReaderReset =>
      logger.info("ReaderReset")
      context become handler(Map.empty[String, Int], Instant.now())
    // wait for the next 1 minute ParseReport event
  }
}