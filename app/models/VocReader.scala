package models

import akka.actor._
import com.github.nscala_time.time.Imports._
import models.ForwardManager.ForwardHourRecord
import play.api._

import java.io.File
import scala.collection.JavaConverters.asScalaBufferConverter
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{FiniteDuration, MINUTES}
import scala.concurrent.{Future, blocking}
import scala.util.{Failure, Success}

case class VocMonitorConfig(id: String, name: String, lat: Double, lng: Double, path: String)

case class VocReaderConfig(enable: Boolean, monitors: Seq[VocMonitorConfig])

object VocReader {
  var count = 0

  def start(configuration: Configuration, actorSystem: ActorSystem, monitorOp: MonitorDB, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB, dataCollectManager: ActorRef): Option[ActorRef] = {
    def getConfig: Option[VocReaderConfig] = {
      def getMonitorConfig(config: Configuration) = {
        val id = config.getString("id").get
        val name = config.getString("name").get
        val lat = config.getDouble("lat").get
        val lng = config.getDouble("lng").get
        val path = config.getString("path").get
        VocMonitorConfig(id, name, lat, lng, path)
      }

      for {config <- configuration.getConfig("vocReader")
           enable <- config.getBoolean("enable") if enable
           monitorConfigs <- config.getConfigSeq("monitors")
           monitors = monitorConfigs.map(getMonitorConfig)
           }
      yield
        VocReaderConfig(enable, monitors)
    }

    for (config <- getConfig if config.enable) yield {
      Logger.info(config.toString)
      config.monitors.foreach(config => {
        val m = Monitor(_id = config.id, desc = config.name, lat = Some(config.lat), lng = Some(config.lng))
        monitorOp.upsertMonitor(m)
      })
      count = count + 1
      actorSystem.actorOf(props(config, monitorTypeOp, recordOp, dataCollectManager), s"vocReader${count}")
    }
  }

  def props(config: VocReaderConfig, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB, dataCollectManager: ActorRef): Props =
    Props(new VocReader(config, monitorTypeOp, recordOp, dataCollectManager))


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

class VocReader(config: VocReaderConfig, monitorTypeOp: MonitorTypeDB, recordOp: RecordDB, dataCollectManager: ActorRef)
  extends Actor with ActorLogging {
  Logger.info("VocReader start")

  import VocReader._
  import ReaderHelper._
  def receive: Receive = handler(mutable.Map.empty[String, mutable.Set[String]], None)

  def handler(parsedMap: mutable.Map[String, mutable.Set[String]], timerOpt: Option[Cancellable]): Receive = {
    case ReadFile =>
      Future {
        blocking {
          try{
            for (monitorConfig <- config.monitors) {
              val parsedFileSet = parsedMap.getOrElseUpdate(monitorConfig.path, mutable.Set.empty[String])
              if (parsedFileSet.isEmpty)
                parsedFileSet ++= getParsedFileList(monitorConfig.path)

              parseMonitor(monitorConfig)(parsedFileSet)
            }
          }catch{
            case ex: Exception =>
              Logger.error("Failed to ReadFile", ex)
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
    val allFileAndDirs = new java.io.File(monitorConfig.path).listFiles().toList
    val dirs = allFileAndDirs.filter(p => p != null && p.isDirectory && !isArchive(p))
    val today = (DateTime.now() - 2.hour).toLocalDate

    for (dir <- dirs) {
      val dirName = dir.getName
      val taiwanYear = dirName.take(3).toInt
      val year = taiwanYear + 1911
      val month = dirName.drop(3).toInt
      parseAllTx0(monitorConfig, year, month)(parsedFileList)

      if (year > today.getYear || (year == today.getYear && month >= today.getMonthOfYear))
        return

      setArchive(dir)
    }
  }

  private def parseAllTx0(monitorConfig: VocMonitorConfig, year: Int, month: Int, ignoreParsed: Boolean = false)(parsedFileList: mutable.Set[String]): Unit = {
    val dir = monitorConfig.path
    val monthFolder = dir + File.separator + s"${year - 1911}${"%02d".format(month)}"

    def listTx0Files = {
      val BP1Files = Option(new java.io.File(monthFolder + File.separator + "BP1").listFiles())
        .getOrElse(Array.empty[File])
      val PlotFiles = Option(new java.io.File(monthFolder + File.separator + "Plot").listFiles())
        .getOrElse(Array.empty[File])
      val allFiles = BP1Files ++ PlotFiles
      allFiles.filter(p =>
        p != null && (ignoreParsed || !parsedFileList.contains(p.getAbsolutePath)))
    }

    val files: Array[File] = listTx0Files
    for (f <- files) {
      if (f.getName.toLowerCase().endsWith("tx0")) {
        try {
          Logger.info(s"parse ${f.getAbsolutePath}")
          for (dateTime <- getFileDateTime(f.getName, year, month)) {
            parser(monitorConfig.id, f, dateTime)
            parsedFileList.add(f.getAbsolutePath)
            appendToParsedFileList(dir, f.getAbsolutePath)
          }
        } catch {
          case ex: Throwable =>
            Logger.error("skip buggy file", ex)
        }
      }
    }
  }

  private def parser(monitorId: String, file: File, dateTime: DateTime): Unit = {
    import com.github.tototoshi.csv._

    val reader = CSVReader.open(file)
    val recordList = reader.all().dropWhile { col => !col.head.startsWith("------") }.drop(1).takeWhile { col => col.head.nonEmpty }
    val dataList =
      for (line <- recordList) yield {
        val mtName = line(2)
        val mtID = "_" + mtName.replace(",", "_").replace("-", "_")
        val mtCase = monitorTypeOp.rangeType(mtID, mtName, "ppb", 2)
        mtCase.measuringBy = Some(List.empty[String])
        monitorTypeOp.ensure(mtCase)

        try {
          val v = line(5).toDouble
          Some((mtID, (v, MonitorStatus.NormalStat)))
        } catch {
          case ex: Throwable =>
            None
        }
      }
    reader.close()
    val mtDataList = dataList.flatten.map(data => {
      val (mt, (value, status)) = data
      val mtCase = monitorTypeOp.map(mt)
      monitorTypeOp.getMinMtRecordByRawValue(mt, Some(value), status)(mtCase.fixedM, mtCase.fixedB)
    })

    val f = recordOp.upsertRecord(recordOp.HourCollection)(RecordList.factory(dateTime.toDate, mtDataList, monitorId))
    f onComplete {
      case Success(_) =>
        dataCollectManager ! ForwardHourRecord(dateTime, dateTime.plusHours(1))

      case Failure(exception) =>
        Logger.error("failed", exception)
    }
  }

  override def postStop(): Unit = {
    Logger.info("VocReader stopped")
  }
}