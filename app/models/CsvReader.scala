package models

import akka.actor._
import com.github.nscala_time.time.Imports._
import com.github.tototoshi.csv.CSVReader
import models.ModelHelper.{errorHandler, getPeriods}
import play.api._

import java.io.File
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}
import scala.util.{Failure, Success}

object CsvReader {
  var count = 0

  def start(environment: play.api.Environment, actorSystem: ActorSystem, monitorOp: MonitorDB, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB, dataCollectManagerOp: DataCollectManagerOp) = {

    val mtList: Seq[String] = Seq(MonitorType.CO2, MonitorType.CH4, MonitorType.O2, MonitorType.N2, MonitorType.N2O2)
    mtList.foreach(monitorTypeOp.ensureMonitorType)

    val docRoot: String = environment.rootPath + "/report_template/"

    val allFileAndDirs = Option(new File(docRoot).listFiles()).getOrElse(Array.empty[File])
    val csvFileLists = allFileAndDirs.filter(p => p != null && p.isFile() && p.getName.endsWith("csv"))

    if (csvFileLists.nonEmpty) {
      count = count + 1
      actorSystem.actorOf(props(csvFileLists.toList, monitorTypeOp, recordOp, dataCollectManagerOp), s"csvReader${count}")
    }

  }

  def props(files: List[File], monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB, dataCollectManagerOp: DataCollectManagerOp): Props =
    Props(new CsvReader(files, monitorTypeOp, recordOp, dataCollectManagerOp))

  case object ReadFile
}

class CsvReader(files: List[File], monitorTypeOp: MonitorTypeDB, recordOp: RecordDB, dataCollectManagerOp: DataCollectManagerOp)
  extends Actor with ActorLogging {
  Logger.info("CsvReader start!")

  import CsvReader._

  val mtList: Seq[String] = Seq(MonitorType.CO2, MonitorType.CH4, MonitorType.O2, MonitorType.N2, MonitorType.N2O2)
  mtList.foreach(monitorTypeOp.ensureMonitorType)

  self ! ReadFile

  def receive: Receive = {
    case ReadFile =>
      Future {
        blocking {
          for (file <- files)
            parser(Monitor.SELF_ID, file)

          self ! PoisonPill
        }
      }
  }

  def parser(monitorId: String, file: File) = {
    Logger.info(s"Start import ${file.getName}")
    val reader = CSVReader.open(file, "UTF-8")
    var count = 0
    val docOpts =
      for (record <- reader.allWithHeaders()) yield
        try {
          val date = LocalDate.parse(record("Date"), DateTimeFormat.forPattern("YYYY/M/d"))
          val time =
            LocalTime.parse(record("Time"), DateTimeFormat.forPattern("HH:mm:ss"))
          val dateTime = date.toDateTime(time).toDate
          val co2 = MtRecord(MonitorType.CO2, record.get("CO2").map(_.toDouble), MonitorStatus.NormalStat)
          val ch4 = MtRecord(MonitorType.CH4, record.get("CH4").map(_.toDouble), MonitorStatus.NormalStat)
          val o2 = MtRecord(MonitorType.O2, record.get("O2").map(_.toDouble), MonitorStatus.NormalStat)
          val n2 = MtRecord(MonitorType.N2, record.get("N2").map(_.toDouble), MonitorStatus.NormalStat)
          val n2o2 = MtRecord(MonitorType.N2O2, record.get("N2/O2").map(_.toDouble), MonitorStatus.NormalStat)
          count = count + 1
          Some(RecordList(time = dateTime, monitor = monitorId,
            mtDataList = Seq(co2, ch4, o2, n2, n2o2)))
        } catch {
          case ex: Throwable =>
            None
        }
    val docs = docOpts.flatten

    reader.close()
    Logger.info(s"Total $count records")

    if (docs.nonEmpty) {
      file.delete()
      val f = recordOp.upsertManyRecords(recordOp.MinCollection)(docs)

      f onFailure errorHandler
      f onComplete {
        case Success(result) =>
          val start = new DateTime(docs.map(_._id.time).min)
          val end = new DateTime(docs.map(_._id.time).max)
          for {
            current <- getPeriods(start, end, new Period(1, 0, 0, 0))} {
            dataCollectManagerOp.recalculateHourData(monitorId, current, forward = false)(monitorTypeOp.mtvList, monitorTypeOp)
          }

        case Failure(exception) =>
          Logger.error("Failed to import data", exception)
      }
    }
  }
}