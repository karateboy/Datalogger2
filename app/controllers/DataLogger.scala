package controllers

import com.github.nscala_time.time.Imports.DateTime
import models._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class DataLogger @Inject()(alarmRuleDb: AlarmRuleDb,
                           recordDB: RecordDB,
                           excelUtility: ExcelUtility,
                           tableType: TableType,
                           security: Security,
                           cc: ControllerComponents,
                           assets: Assets) extends AbstractController(cc) {
  val logger: Logger = Logger(this.getClass)

  def getAlarmRules: Action[AnyContent] = security.Authenticated.async {
    implicit request =>
      for (ret <- alarmRuleDb.getRulesAsync) yield {
        implicit val w1: OWrites[AlarmRule] = Json.writes[AlarmRule]
        Ok(Json.toJson(ret))
      }
  }

  def upsertAlarmRule: Action[JsValue] = security.Authenticated.async(parse.json) {
    implicit request =>
      implicit val r1 = Json.reads[AlarmRule]
      val result = request.body.validate[AlarmRule]
      result.fold(err => {
        logger.error(JsError(err).toString)
        Future.successful(BadRequest(Json.obj("ok" -> false, "msg" -> JsError(err).toString())))
      },
        rule => {
          for (_ <- alarmRuleDb.upsertAsync(rule)) yield
            Ok(Json.obj("ok" -> true))
        })
  }

  def deleteAlarmRule(id: String): Action[AnyContent] = security.Authenticated.async {
    implicit request =>
      alarmRuleDb.deleteAsync(id).map { _ =>
        Ok(Json.obj("ok" -> true))
      }
  }

  def getUpsertTemplate: Action[AnyContent] = security.Authenticated.async {
    implicit request =>
      assets.at("/public", "upsertTemplate.xlsx")(request)
  }

  def upsertData: Action[MultipartFormData[play.api.libs.Files.TemporaryFile]] = security.Authenticated.async(parse.multipartFormData) {
    implicit request =>
      val file = request.body.file("data").get
      val tmpFile = file.ref.path.toFile

      val minRecordLists = excelUtility.getUpsertMinData(tmpFile)
      if (minRecordLists.isEmpty) {
        Future.successful(BadRequest(Json.obj("ok" -> false, "msg" -> "No data")))
      } else {
        logger.info(s"upsert minData #=${minRecordLists.size}")
        if(minRecordLists.nonEmpty)
          logger.info("1st record=>" + minRecordLists.head.toString)

        for (_ <- recordDB.upsertManyRecords(recordDB.MinCollection)(minRecordLists)) yield {
          Ok(Json.obj("ok" -> true))
        }
      }
  }

  def queryData(monitorStr: String, monitorTypeStr: String, tabTypeStr: String, startNum: Long, endNum: Long): Action[AnyContent] = Action.async {
    val monitors = monitorStr.split(':')
    val monitorTypes = monitorTypeStr.split(':')
    val tabType = tableType.withName(tabTypeStr)
    val (start, end) =
      if (tabType == tableType.hour) {
        val original_start = new DateTime(startNum)
        val original_end = new DateTime(endNum)
        (original_start.withMinuteOfHour(0), original_end.withMinuteOfHour(0))
      } else {
        (new DateTime(startNum), new DateTime(endNum))
      }

    val resultFuture: Future[Seq[RecordList]] = recordDB.getRecordListFuture(tableType.mapCollection(tabType))(start, end, monitors)
    implicit val recordListIDwrite: OWrites[RecordListID] = Json.writes[RecordListID]
    implicit val mtDataWrite: OWrites[MtRecord] = Json.writes[MtRecord]
    implicit val recordListWrite: OWrites[RecordList] = Json.writes[RecordList]
    for (result <- resultFuture) yield {
      result.foreach(rs => rs.mtDataList = rs.mtDataList.filter(mtData => monitorTypes.contains(mtData.mtName)))
      Ok(Json.toJson(result))
    }
  }

}