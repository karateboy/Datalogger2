package controllers

import com.github.nscala_time.time.Imports._
import models._
import play.api.Configuration
import play.api.libs.json._
import play.api.mvc._

import java.util.Date
import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class Realtime @Inject()
(monitorTypeOp: MonitorTypeDB, dataCollectManagerOp: DataCollectManagerOp, instrumentOp: InstrumentDB,
 monitorStatusOp: MonitorStatusDB, configuration:Configuration, aisDB: AisDB,
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
                if(instrumentStatus == "停用")
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

  case class ParsedAisData(monitor: String, time: Date, ships: Seq[Map[String, String]])
  case class LatestAisData(enable:Boolean, aisData:Seq[ParsedAisData])
  def getLatestAisData(): Action[AnyContent] = Security.Authenticated.async {
    implicit val w1 = Json.writes[ParsedAisData]
    implicit val write = Json.writes[LatestAisData]
    if(!AisDataCollector.enable){
      Future.successful(Ok(Json.toJson(LatestAisData(false, Seq.empty[ParsedAisData]))))
    }else {
      val futures = monitorDB.mvList.map(m=>aisDB.getLatestData(m))
      for(ret <- Future.sequence(futures)) yield {
        val parsed = ret.flatten.map(ais=>{
          val shipList = Json.parse(ais.json).validate[Seq[Map[String, String]]].get
          ParsedAisData(ais.monitor, ais.time, shipList)
        })
        Ok(Json.toJson(LatestAisData(true, parsed)))
      }
    }
  }

def getLatestMonitorData() = Security.Authenticated {
    val futures = for(monitor<-monitorDB.mvList) yield

  }
}