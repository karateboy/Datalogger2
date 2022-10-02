package controllers

import com.github.nscala_time.time.Imports._
import models.ModelHelper.errorHandler
import models._
import play.api.{Configuration, Logger}
import play.api.libs.json._
import play.api.mvc._

import java.util.Date
import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class Realtime @Inject()
(monitorTypeOp: MonitorTypeDB, dataCollectManagerOp: DataCollectManagerOp, instrumentOp: InstrumentDB,
 monitorStatusOp: MonitorStatusDB, configuration: Configuration, aisDB: AisDB,
 monitorDB: MonitorDB, recordDB: RecordDB) extends Controller {
  val overTimeLimit = 6

  case class MonitorTypeStatus(_id: String, desp: String, value: String, unit: String, instrument: String, status: String, classStr: Seq[String], order: Int)

  def MonitorTypeStatusList() = Security.Authenticated.async {
    implicit request =>

      implicit val mtsWrite = Json.writes[MonitorTypeStatus]

      val result =
        for {
          instrumentMap <- instrumentOp.getInstrumentMap()
          dataMap <- dataCollectManagerOp.getLatestData()
        } yield {
          val list = {
            for {
              mt <- monitorTypeOp.realtimeMtvList.sortBy(monitorTypeOp.map(_).order)
              recordOpt = dataMap.get(mt)
            } yield {
              val mCase = monitorTypeOp.map(mt)
              val measuringByStr = mCase.measuringBy.map {
                instrumentList =>
                  instrumentList.mkString(",")
              }.getOrElse("??")

              def instrumentStatus() = {
                val ret =
                  for (measuringBy <- mCase.measuringBy) yield {
                    val instruments: List[Instrument] =
                      measuringBy.map(instrumentMap.get(_)).flatten

                    if (instruments.exists(inst => !inst.active))
                      "停用"
                    else
                      "斷線"
                  }
                ret.getOrElse("設備啟動中")
              }

              if (recordOpt.isDefined) {
                val record = recordOpt.get
                val duration = new Duration(record.time, DateTime.now())
                val (overInternal, overLaw) = monitorTypeOp.overStd(mt, record.value)
                val status = if (duration.getStandardSeconds <= overTimeLimit)
                  monitorStatusOp.map(record.status).desp
                else
                  instrumentStatus

                MonitorTypeStatus(_id = mCase._id, desp = mCase.desp, monitorTypeOp.format(mt, record.value),
                  mCase.unit, measuringByStr,
                  status,
                  MonitorStatus.getCssClassStr(record.status, overInternal, overLaw), mCase.order)
              } else {
                if (instrumentStatus == "停用")
                  MonitorTypeStatus(_id = mCase._id, mCase.desp, monitorTypeOp.format(mt, None),
                    mCase.unit, measuringByStr,
                    instrumentStatus,
                    Seq("stop_status"), mCase.order)
                else
                  MonitorTypeStatus(_id = mCase._id, mCase.desp, monitorTypeOp.format(mt, None),
                    mCase.unit, measuringByStr,
                    instrumentStatus,
                    Seq("disconnect_status"), mCase.order)
              }
            }
          }

          Ok(Json.toJson(list))
        }

      result
  }

  case class ParsedAisData(monitor: String, time: Date, ships: Seq[Map[String, String]],
                           lat: Option[Double], lng: Option[Double])

  case class LatestAisData(enable: Boolean, aisData: Seq[ParsedAisData])

  def getLatestAisData(): Action[AnyContent] = Security.Authenticated.async {
    implicit val w1 = Json.writes[ParsedAisData]
    implicit val write = Json.writes[LatestAisData]
    if(!AisDataCollector.enable)
      Logger.info(s"AIS Data collection is disabled. History data is returned.")

    val monitorsLatestFuture = Future.sequence(monitorDB.mvListOfNoEpa.map(
      recordDB.getLatestMonitorRecordAsync(recordDB.MinCollection)(_)))
    val aisFutures = Future.sequence(monitorDB.mvListOfNoEpa.map(m => aisDB.getLatestData(m)))
    for {
      monitorLatestData <- monitorsLatestFuture
      aisData <- aisFutures
    } yield {
      val monitorDataMap: Map[String, Map[String, MtRecord]] = monitorLatestData.flatten.map(record => record._id.monitor -> record.mtMap).toMap
      val parsed = aisData.flatten.map(ais => {
        val shipList = Json.parse(ais.json).validate[Seq[Map[String, String]]].get
        val monitorLat = monitorDataMap.get(ais.monitor).flatMap(_.get(MonitorType.LAT).flatMap(_.value))
        val monitorLng = monitorDataMap.get(ais.monitor).flatMap(_.get(MonitorType.LNG).flatMap(_.value))
        ParsedAisData(monitor = ais.monitor, time = ais.time, ships = shipList, lat = monitorLat, lng = monitorLng)
      })
      Ok(Json.toJson(LatestAisData(true, parsed)))
    }
  }

  case class AisDataResp(columns:Seq[String], ships:Seq[Map[String, String]])
  def getNearestAisDataInThePast(monitor: String, respType:String, startNum: Long) = Security.Authenticated.async {
    val start = new DateTime(startNum).toDate
    val f = aisDB.getNearestAisDataInThePast(monitor, respType, start)
    f onFailure errorHandler
    for(ret<-f) yield {
      implicit val w = Json.writes[AisDataResp]
      if(ret.isEmpty)
        Ok(Json.toJson(AisDataResp(Seq.empty[String], Seq.empty[Map[String,String]])))
      else{
        val shipList = Json.parse(ret.get.json).validate[Seq[Map[String, String]]].get
        import collection.mutable.Set
        val columnSet = Set.empty[String]
        shipList.foreach(_.keys.foreach(columnSet.add))
        Ok(Json.toJson(AisDataResp(columnSet.toSeq, shipList)))
      }
    }
  }
  case class LatestMonitorData(monitorTypes: Seq[String], monitorData: Seq[RecordList])

  def getLatestMonitorData() = Security.Authenticated.async {
    implicit val writes = Json.writes[LatestMonitorData]
    val retListF = Future.sequence(for (monitor <- monitorDB.mvListOfNoEpa) yield
      recordDB.getLatestMonitorRecordAsync(recordDB.MinCollection)(monitor))

    for (retList <- retListF) yield {
      val monitorRecordList = retList.flatten
      import scala.collection.mutable.Set
      val monitorTypeSet = Set.empty[String]
      monitorRecordList.foreach(recordList =>
        recordList.mtDataList.foreach(mtRecord =>
          monitorTypeSet.add(mtRecord.mtName)))
      val monitorTypes = monitorTypeSet.toList.sortBy(mt => monitorTypeOp.map(mt).order)
      Ok(Json.toJson(LatestMonitorData(monitorTypes, monitorRecordList)))
    }
  }
}