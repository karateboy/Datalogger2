package controllers

import com.github.nscala_time.time.Imports._
import models.ModelHelper.waitReadyResult
import models._
import play.api.libs.json._
import play.api.mvc._

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class Realtime @Inject()
(monitorTypeOp: MonitorTypeOp, dataCollectManagerOp: DataCollectManagerOp,
 monitorStatusOp: MonitorStatusOp, groupOp: GroupOp, recordOp: RecordOp, userOp: UserOp) extends Controller {
  val overTimeLimit = 6

  case class MonitorTypeStatus(_id: String, desp: String, value: String, unit: String, instrument: String, status: String, classStr: Seq[String], order: Int)

  def MonitorTypeStatusList(): Action[AnyContent] = Security.Authenticated.async {
    implicit request =>
      val groupID = request.user.group
      val groupMtMap = waitReadyResult(monitorTypeOp.getGroupMapAsync(groupID))


      implicit val mtsWrite = Json.writes[MonitorTypeStatus]

      val result =
        for (dataMap <- dataCollectManagerOp.getLatestData) yield {
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

                MonitorTypeStatus(_id = mCase._id, desp = mCase.desp, monitorTypeOp.format(mt, Some(record.value)), mCase.unit, measuringByStr,
                  monitorStatusOp.map(record.status).desp,
                  MonitorStatus.getCssClassStr(record.status, overInternal, overLaw), mCase.order)
              } else {
                MonitorTypeStatus(_id = mCase._id, mCase.desp, monitorTypeOp.format(mt, None), mCase.unit, measuringByStr,
                  "通訊中斷",
                  Seq("abnormal_status"), mCase.order)
              }
            }
          Ok(Json.toJson(list))
        }

      result
  }

  case class MtSummary(mt: String, max: Option[Double], normal:Boolean)
  case class RealtimeSummary(mtSummaries: Seq[MtSummary], connected: Int, disconnected: Int)

  def realtimeSummary(): Action[AnyContent] = Security.Authenticated.async {
    implicit request =>
      val userInfo = request.user
      val groupID = userInfo.group
      val group = groupOp.map(groupID)
      val user = userOp.getUserByEmail(userInfo.id).get
      val groupMtMap = waitReadyResult(monitorTypeOp.getGroupMapAsync(groupID))
      val monitors = group.monitors
      val tabType = TableType.min
      val oneHourAgo = DateTime.now.minusHours(1).toDate
      val futures = for (m <- monitors) yield
        recordOp.getLatestRecordWithOldestLimitFuture(TableType.mapCollection(tabType))(m, oneHourAgo)

      val allFutures = Future.sequence(futures)

      for (allRecords <- allFutures) yield {
        val monitorLatestRecordLists = allRecords.fold(Seq.empty[RecordList])((a, b) => {
          a ++ b
        })

        def getMtRecordValues(mt: String) = monitorLatestRecordLists.flatMap {
          recordList =>
            recordList.mtMap.get(mt).map(_.value)
        }

        val mtSummaries =
          for(mt <- user.monitorTypeOfInterest) yield {
            val mtValues = getMtRecordValues(mt)
            val mtMax = if (mtValues.isEmpty)
              None
            else
              Some(mtValues.max)

            val isNormal =
              if(mtMax.isEmpty)
                false
              else{
                val (overInternal, overLaw) = monitorTypeOp.overStd(mt, mtMax.get, groupMtMap)
                !overInternal && !overLaw
              }
            MtSummary(mt, mtMax, isNormal)
          }

        val connected = monitorLatestRecordLists.count(r => r.mtDataList.nonEmpty)
        implicit val write0: OWrites[MtSummary] = Json.writes[MtSummary]
        implicit val write: OWrites[RealtimeSummary] = Json.writes[RealtimeSummary]
        Ok(Json.toJson(RealtimeSummary(mtSummaries, connected, monitors.length - connected)))
      }
  }

}