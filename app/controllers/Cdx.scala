package controllers

import com.github.nscala_time.time.Imports.DateTime
import models.ModelHelper.errorHandler
import models.{CdxUploader, MonitorTypeDB, NewTaipeiOpenData, RecordDB, SysConfigDB}
import play.api.{Environment, Logger}
import play.api.libs.json.{JsError, JsValue, Json}
import play.api.mvc.{Action, AnyContent, BodyParsers, Controller}

import java.nio.file.Paths
import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class Cdx @Inject()(cdxUploader: CdxUploader,
                    monitorTypeDB: MonitorTypeDB,
                    sysConfigDB: SysConfigDB,
                    recordDB: RecordDB,
                    environment: Environment,
                    newTaipeiOpenData: NewTaipeiOpenData) extends Controller {

  import CdxUploader._

  def getConfig: Action[AnyContent] = Security.Authenticated.async {
    val f = sysConfigDB.getCdxConfig
    f onFailure errorHandler
    for (config <- f) yield
      Ok(Json.toJson(config))
  }

  def putConfig: Action[JsValue] = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      val ret = request.body.validate[CdxConfig]
      ret.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          Future.successful(BadRequest(JsError.toJson(error).toString))
        },
        config => {
          sysConfigDB.setCdxConfig(config)
          Future.successful(Ok("ok"))
        })
  }

  def getMonitorTypes: Action[AnyContent] = Security.Authenticated.async {
    val f = sysConfigDB.getCdxMonitorTypes
    f onFailure errorHandler()
    for (monitorTypes <- f) yield
      Ok(Json.toJson(monitorTypes))
  }

  def putMonitorTypes: Action[JsValue] = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      val ret = request.body.validate[Seq[CdxMonitorType]]
      ret.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          Future.successful(BadRequest(JsError.toJson(error).toString))
        },
        monitorTypes => {
          val validated = CdxUploader.itemIdMap.keys.toList.sorted.map{
            mt =>
              monitorTypes.find(cdxMt=>cdxMt.mt == mt).getOrElse(CdxMonitorType(mt, monitorTypeDB.map(mt).desp, None, None))
          }

          sysConfigDB.setCdxMonitorTypes(validated)
          Future.successful(Ok("ok"))
        })
  }

  def CdxUploadData(startNum:Long, endNum:Long): Action[AnyContent] = Security.Authenticated.async {
    val start = new DateTime(startNum)
    val end = new DateTime(endNum)
    val recordFuture = recordDB.getRecordListFuture(recordDB.HourCollection)(start, end)
    val uploadPath = environment.rootPath.toPath.resolve("cdxUpload")
    for{records<-recordFuture
        cdxConfig <- sysConfigDB.getCdxConfig
        cdxMtConfigs <- sysConfigDB.getCdxMonitorTypes
        } yield {
      records.filter(record=>record.mtDataList.nonEmpty).foreach(record=>cdxUploader.upload(record, cdxConfig, cdxMtConfigs))
      Ok(Json.obj("ok"->true))
    }
  }

  def newTaipeiOpenDataUpload(startNum:Long, endNum:Long): Action[AnyContent] = Security.Authenticated.async {
    val start = new DateTime(startNum)
    val end = new DateTime(endNum)
    val recordFuture = recordDB.getRecordListFuture(recordDB.HourCollection)(start, end)
    for{records<-recordFuture
        cdxMtConfigs <- sysConfigDB.getCdxMonitorTypes
        } yield {
      records.filter(record=>record.mtDataList.nonEmpty).foreach(record=>newTaipeiOpenData.upload(record, cdxMtConfigs))
      Ok(Json.obj("ok"->true))
    }
  }
}
