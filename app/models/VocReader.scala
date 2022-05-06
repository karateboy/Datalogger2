package models

import akka.actor._
import com.github.nscala_time.time.Imports._
import models.ModelHelper.errorHandler
import models.mongodb.RecordOp
import org.mongodb.scala.result.UpdateResult
import play.api._

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardOpenOption}
import scala.collection.JavaConverters.asScalaBufferConverter
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, FiniteDuration, MINUTES, SECONDS}
import scala.concurrent.{Future, blocking}

case class VocMonitorConfig(id: String, name: String, lat: Double, lng: Double, path: String)

case class VocReaderConfig(enable: Boolean, monitors: Seq[VocMonitorConfig])

object VocReader {
  var count = 0

  def start(configuration: Configuration, actorSystem: ActorSystem, monitorOp: MonitorDB, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB) = {
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

    for (config <- getConfig if config.enable) {
      Logger.info(config.toString)
      config.monitors.foreach(config=>{
        val m = Monitor(_id = config.id, desc = config.name, lat = Some(config.lat), lng = Some(config.lng))
        monitorOp.upsert(m)
      })
      count = count + 1
      actorSystem.actorOf(props(config, monitorTypeOp, recordOp), s"vocReader${count}")
    }
  }

  def props(config: VocReaderConfig, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB) =
    Props(classOf[VocReader], config, monitorTypeOp, recordOp)


  def getFileDateTime(fileName: String, year: Int, month: Int): Option[DateTime] = {
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

  case class ReparseDir(year: Int, month: Int)

  case object ReadFile

  def getParsedFileList(dir:String) = {
    val parsedFileName = s"$dir/parsed.list"
      try {
        Files.readAllLines(Paths.get(parsedFileName), StandardCharsets.UTF_8).asScala
      } catch {
        case ex: Throwable =>
          Logger.info(s"Cannot open $parsedFileName")
          Seq.empty[String]
      }
  }

  def appendToParsedFileList(dir:String, filePath: String) = {
    var parsedFileList = getParsedFileList(dir)
    parsedFileList = parsedFileList ++ Seq(filePath)

    try {
      Files.write(Paths.get(s"$dir/parsed.list"), (filePath + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    } catch {
      case ex: Throwable =>
        Logger.warn(ex.getMessage)
    }
  }
  def setArchive(f: File) {
    import java.nio.file._
    import java.nio.file.attribute.DosFileAttributeView

    val path = Paths.get(f.getAbsolutePath)
    val dfav = Files.getFileAttributeView(path, classOf[DosFileAttributeView])
    dfav.setArchive(true)
  }
  def isArchive(f: File) = {
    import java.nio.file._
    import java.nio.file.attribute.DosFileAttributes

    val dfa = Files.readAttributes(Paths.get(f.getAbsolutePath), classOf[DosFileAttributes])
    dfa.isArchive()
  }
}

class VocReader(config: VocReaderConfig, monitorTypeOp: MonitorTypeDB, recordOp: RecordDB)
  extends Actor with ActorLogging {
  Logger.info("VocReader start")
  import VocReader._

  var timer = context.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, ReadFile)

  def receive = {
    case ReadFile =>
      Future {
        blocking {
          for (monitorConfig <- config.monitors)
            parseMonitor(monitorConfig)
            //parseAllTx0(monitorConfig, today.getYear, today.getMonthOfYear)
          timer = resetTimer
        }
      }

    case ReparseDir(year: Int, month: Int) =>
    //parseAllTx0(dir, year, month, true)
  }

  def resetTimer: Cancellable = {
    import scala.concurrent.duration._
    context.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ReadFile)
  }

  def parseMonitor(monitorConfig: VocMonitorConfig): Unit ={
    val allFileAndDirs = new java.io.File(monitorConfig.path).listFiles().toList
    val dirs = allFileAndDirs.filter(p => p != null && p.isDirectory() && !isArchive(p))
    val today = (DateTime.now() - 2.hour).toLocalDate

    for(dir<-dirs) {
      val dirName = dir.getName
      val year = dirName.take(3).toInt
      val month = dirName.drop(3).toInt
      parseAllTx0(monitorConfig, year + 1911, month)
      if(year != today.getYear && month != today.getMonthOfYear)
        setArchive(dir)
    }
  }

  def parseAllTx0(monitorConfig: VocMonitorConfig, year: Int, month: Int, ignoreParsed: Boolean = false) = {
    val dir = monitorConfig.path
    val monthFolder = dir + File.separator + s"${year - 1911}${"%02d".format(month)}"

    Logger.info(s"parsing ${monitorConfig.name} $monthFolder")
    val parsedFileList = VocReader.getParsedFileList(dir)
    def listTx0Files = {
      val BP1Files = Option(new java.io.File(monthFolder + File.separator + "BP1").listFiles())
        .getOrElse(Array.empty[File])
      val PlotFiles = Option(new java.io.File(monthFolder + File.separator + "Plot").listFiles())
        .getOrElse(Array.empty[File])
      val allFiles = BP1Files ++ PlotFiles
      allFiles.filter(p =>
        p != null && (ignoreParsed || !parsedFileList.contains(p.getAbsolutePath)))
    }
    Logger.info(s"listTx0Files #=${listTx0Files.length}")

    val files: Array[File] = listTx0Files
    for (f <- files) {
      if (f.getName.toLowerCase().endsWith("tx0")) {
        try {
          Logger.info(s"parse ${f.getAbsolutePath}")
          for (dateTime <- getFileDateTime(f.getName, year, month)) {
            parser(monitorConfig.id, f, dateTime)
            appendToParsedFileList(dir, f.getAbsolutePath)
          }
        } catch {
          case ex: Throwable =>
            Logger.error("skip buggy file", ex)
        }
      }
    }
  }

  def parser(monitorId:String, file: File, dateTime: DateTime): Future[UpdateResult] = {
    import com.github.tototoshi.csv._

    val reader = CSVReader.open(file)
    val recordList = reader.all().dropWhile { col => !col(0).startsWith("------") }.drop(1).takeWhile { col => !col(0).isEmpty() }
    val dataList =
      for (line <- recordList) yield {
        val mtName = line(2)
        val mtID = "_" + mtName.replace(",", "_").replace("-", "_")
        val mtCase = monitorTypeOp.rangeType(mtID, mtName, "ppb", 2)
        mtCase.measuringBy = Some(List.empty[String])
        if (!monitorTypeOp.exist(mtCase))
          monitorTypeOp.ensureMonitorType(mtCase)

        try {
          val v = line(5).toDouble
          Some((mtID, (v, MonitorStatus.NormalStat)))
        } catch {
          case ex: Throwable =>
            None
        }
      }
    reader.close()
    val mtDataList = dataList.flatten.map(d=>MtRecord(d._1, Some(d._2._1), d._2._2))
    val rl = RecordList(dateTime.toDate, mtDataList, monitorId)
    val f = recordOp.upsertRecord(rl)(recordOp.HourCollection)
    f onFailure(errorHandler)
    f
  }

  override def postStop(): Unit = {
    timer.cancel()
  }
}