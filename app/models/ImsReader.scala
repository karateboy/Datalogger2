package models

import akka.actor._
import com.github.nscala_time.time.Imports._
import com.github.tototoshi.csv.CSVReader
import models.ForwardManager.{ForwardHourRecord, ForwardMinRecord}
import models.ImsReader.ImsConfig
import models.ModelHelper.{getPeriods, waitReadyResult}
import play.api._

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.collection.JavaConverters.asScalaBufferConverter
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}

object ImsReader {
  private case object ReadFile

  case class ImsConfig(enable: Boolean, path: String)

  val logger: Logger = Logger(getClass)
  var managerOpt: Option[ActorRef] = None
  var count = 0
  var rootPath: Path = _

  def start(configuration: Configuration,
            actorSystem: ActorSystem,
            monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB,
            dataCollectManager: ActorRef,
            dataCollectManagerOp: DataCollectManagerOp,
            environment: Environment): Option[ActorRef] = {

    rootPath = environment.rootPath.toPath

    def getConfig: Option[ImsConfig] = {
      for {config <- configuration.getOptional[Configuration]("Ims")
           enable <- config.getOptional[Boolean]("enable") if enable
           path <- config.getOptional[String]("path")
           } yield {
        ImsConfig(enable, path)
      }
    }

    for (config <- getConfig if config.enable) yield {
      logger.info(config.toString)
      count += 1
      actorSystem.actorOf(props(config, monitorTypeOp, recordOp, dataCollectManager, dataCollectManagerOp, actorSystem), s"imsReader$count")
    }
  }


  def props(config: ImsConfig, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB, dataCollectManager: ActorRef, dataCollectManagerOp: DataCollectManagerOp, actorSystem: ActorSystem): Props =
    Props(new ImsReader(config, monitorTypeOp, recordOp, dataCollectManager, dataCollectManagerOp, actorSystem))

  private case class ParseInfo(modifiedTime: FileTime, skip: Int)

  private val parsedFileName = "parsedFiles.txt"
  private val parsedInfoMap: mutable.Map[String, ParseInfo] = mutable.Map.empty[String, ParseInfo]

  private def initParsedInfoMap(): Unit = {
    try {
      for (parsedInfo <- Files.readAllLines(rootPath.resolve(parsedFileName), StandardCharsets.UTF_8).asScala) {
        val token = parsedInfo.split(":")
        val filePath = token(0)
        val modifiedTime = FileTime.fromMillis(token(1).toLong)
        val skip = token(2).toInt
        parsedInfoMap.update(filePath, ParseInfo(modifiedTime, skip))
      }
    } catch {
      case _: Throwable =>
        logger.info("Init parsed.lst")
        mutable.Set.empty[String]
    }
  }

  private def updateParsedInfoMap(filePath: String, modifiedTime: Long, skip: Int): Unit = {
    parsedInfoMap.update(filePath, ParseInfo(FileTime.fromMillis(modifiedTime), skip))
    try {
      Files.write(rootPath.resolve(parsedFileName), s"$filePath:$modifiedTime:$skip\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    } catch {
      case ex: Throwable =>
        logger.warn(ex.getMessage)
    }
  }

  import java.io.File

  val mtNames: List[String] = List("HCL", "HF", "NH3", "HNO3", "AcOH")

  private def listFiles(srcPath: String): List[(File, Long)] = {
    logger.info(s"listDirs $srcPath")
    val allFileAndDirs = Option(new java.io.File(srcPath).listFiles()).getOrElse(Array.empty[File])
      .map(f => (f, Files.getLastModifiedTime(f.toPath).toMillis))
    val files = allFileAndDirs.filter(p => {
      val (file, modifiedTime) = p
      file != null &&
        file.isFile &&
        file.getAbsolutePath.endsWith("csv") &&
        (!parsedInfoMap.contains(file.getAbsolutePath) ||
          parsedInfoMap(file.getAbsolutePath).modifiedTime.toMillis != modifiedTime)
    }).toList

    val dirs = allFileAndDirs.filter(pair => pair._1 != null && pair._1.isDirectory)
    if (dirs.isEmpty) {
      files
    } else {
      val deepDir = dirs flatMap (dir => listFiles(dir._1.getAbsolutePath))
      files ++ deepDir
    }
  }

}

private class ImsReader(config: ImsConfig,
                        monitorTypeDB: MonitorTypeDB,
                        recordDB: RecordDB,
                        dataCollectManager: ActorRef,
                        dataCollectManagerOp: DataCollectManagerOp,
                        actorSystem: ActorSystem) extends Actor {

  import ImsReader._

  logger.info(s"ImsReader start $config")
  initParsedInfoMap()
  mtNames.foreach(mt=>{
    monitorTypeDB.ensure(mt)
    recordDB.ensureMonitorType(mt)
  })

  def resetTimer(t: Int): Cancellable = {
    import scala.concurrent.duration._
    actorSystem.scheduler.scheduleOnce(FiniteDuration(t, SECONDS), self, ReadFile)
  }

  private def parseCsv(srcDir: String): Unit = {
    for ((file, modifiedTime) <- listFiles(srcDir)) {
      try {
        logger.info(s"parse ${file.getAbsolutePath}")
        val parsedNum = waitReadyResult(parser(file))
        if (parsedNum != 0) {
          val parseInfo = parsedInfoMap.getOrElseUpdate(file.getAbsolutePath, ParseInfo(FileTime.fromMillis(0), 0))
          updateParsedInfoMap(file.getAbsolutePath, modifiedTime, parseInfo.skip + parsedNum)
        }
      } catch {
        case ex: Throwable =>
          logger.error("skip buggy file", ex)
      }
    }
  }

  def parser(file: File, skip: Int = 0): Future[Int] = {
    val reader = CSVReader.open(file, "UTF-8")
    val recordLists = reader.allWithHeaders()

    def handleDoc(map: Map[String, String]): Future[Option[DateTime]] = {
      try {
        val date =
          LocalDate.parse(map("Date"), DateTimeFormat.forPattern("YYYY/M/d"))

        val time = LocalTime.parse(map("Time"), DateTimeFormat.forPattern("HH:mm:ss")).withSecondOfMinute(0)
        val dateTime = date.toDateTime(time)

        val mtDataList =
          for (mt <- mtNames) yield {
            try {
              if (mt == "HCL") {
                Some(MtRecord(mt, Some(map("HCl").split("\\s+")(0).toDouble), MonitorStatus.NormalStat))
              } else
                Some(MtRecord(mt, Some(map(mt).split("\\s+")(0).toDouble), MonitorStatus.NormalStat))
            } catch {
              case _: Throwable =>
                None
            }
          }
        for (_ <- recordDB.upsertRecord(recordDB.MinCollection)(RecordList.factory(dateTime.toDate, mtDataList.flatten, Monitor.activeId))) yield
          Some(dateTime)
      } catch {
        case ex: Throwable =>
          logger.error(s"fail to parse ${file.getName}", ex)
          Future.successful(None)
      }
    }

    val dateTimeListFuture =
      for (map <- recordLists.drop(skip)) yield
        handleDoc(map)

    reader.close()

    for (dateTimeList <- Future.sequence(dateTimeListFuture)) yield {
      val orderedTimeList = dateTimeList.flatten.sorted
      if (orderedTimeList.nonEmpty) {
        val start = orderedTimeList.head
        val end = orderedTimeList.last
        dataCollectManager ! ForwardMinRecord(start, end.plusMinutes(1))

        val startHour = start.withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0)
        val endHour = end.withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0).plusHours(1)
        for (hour <- getPeriods(startHour, endHour, Period.hours(1)))
          dataCollectManagerOp.recalculateHourData(Monitor.activeId, hour)

        orderedTimeList.size
      } else
        0
    }
  }

  var timer: Cancellable = resetTimer(5)

  def receive: Receive = handler

  def handler: Receive = {
    case ReadFile =>
      logger.info("Start read files")
      Future {
        blocking {
          parseCsv(config.path)
          timer = resetTimer(600)
        }
      }
  }

  override def postStop(): Unit =
    timer.cancel()
}