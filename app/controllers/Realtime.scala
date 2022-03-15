package controllers
import com.github.nscala_time.time.Imports._
import models.ModelHelper.waitReadyResult
import models._
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.ExecutionContext.Implicits.global
import javax.inject._

class Realtime @Inject()
(monitorTypeOp: MonitorTypeOp, dataCollectManagerOp: DataCollectManagerOp,
 monitorStatusOp: MonitorStatusOp) extends Controller {
  val overTimeLimit = 6
  case class MonitorTypeStatus(_id:String, desp: String, value: String, unit: String, instrument: String, status: String, classStr: Seq[String], order: Int)
  def MonitorTypeStatusList() = Security.Authenticated.async {
    implicit request =>
      val groupID = request.user.group
      val groupMtMap = waitReadyResult(monitorTypeOp.getGroupMapAsync(groupID))


      implicit val mtsWrite = Json.writes[MonitorTypeStatus]

      val result =
        for (dataMap <- dataCollectManagerOp.getLatestData()) yield {
          val list =
            for {
              mt <- monitorTypeOp.realtimeMtvList
              recordOpt = dataMap.get(mt)
            } yield {
              val mCase = monitorTypeOp.map(mt)
              val measuringByStr = mCase.measuringBy.map {
                instrumentList =>
                  instrumentList.mkString(",")
              }.getOrElse("??")

              if (recordOpt.isDefined) {
                val record = recordOpt.get
                val duration = new Duration(record.time, DateTime.now())
                val (overInternal, overLaw) = monitorTypeOp.overStd(mt, record.value, groupMtMap)
                val status = if (duration.getStandardSeconds <= overTimeLimit)
                  monitorStatusOp.map(record.status).desp
                else
                  "通訊中斷"

                MonitorTypeStatus(_id=mCase._id, desp=mCase.desp, monitorTypeOp.format(mt, Some(record.value)), mCase.unit, measuringByStr,
                  monitorStatusOp.map(record.status).desp,
                  MonitorStatus.getCssClassStr(record.status, overInternal, overLaw), mCase.order)
              } else {
                MonitorTypeStatus(_id=mCase._id, mCase.desp, monitorTypeOp.format(mt, None), mCase.unit, measuringByStr,
                  "通訊中斷",
                  Seq("abnormal_status"), mCase.order)
              }
            }
          Ok(Json.toJson(list))
        }

      result
  }
}