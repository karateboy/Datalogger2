package controllers

import com.github.nscala_time.time.Imports.DateTime
import models.ModelHelper.errorHandler
import models.{CdxUploader, RecordDB, SysConfigDB}
import play.api.{Environment, Logger}
import play.api.libs.json.{JsError, Json}
import play.api.mvc.{Action, AnyContent, BodyParsers, Controller}

import java.nio.file.Paths
import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class Cdx @Inject()(cdxUploader: CdxUploader, sysConfigDB: SysConfigDB, recordDB: RecordDB, environment: Environment) extends Controller {

  import CdxUploader._

  def getConfig: Action[AnyContent] = Security.Authenticated.async {
    val f = sysConfigDB.getCdxConfig()
    f onFailure errorHandler
    for (config <- f) yield
      Ok(Json.toJson(config))
  }

  def putConfig = Security.Authenticated.async(BodyParsers.parse.json) {
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

  def getMonitorTypes = Security.Authenticated.async {
    val f = sysConfigDB.getCdxMonitorTypes()
    f onFailure errorHandler()
    for (monitorTypes <- f) yield
      Ok(Json.toJson(monitorTypes))
  }

  def putMonitorTypes = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      val ret = request.body.validate[Seq[CdxMonitorType]]
      ret.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          Future.successful(BadRequest(JsError.toJson(error).toString))
        },
        monitorTypes => {
          sysConfigDB.setCdxMonitorTypes(monitorTypes)
          Future.successful(Ok("ok"))
        })
  }

  def CdxUploadData(startNum:Long, endNum:Long): Action[AnyContent] = Security.Authenticated.async {
    val start = new DateTime(startNum)
    val end = new DateTime(endNum)
    val recordFuture = recordDB.getRecordListFuture(recordDB.HourCollection)(start, end)
    val uploadPath = environment.rootPath.toPath.resolve("cdxUpload")
    Logger.info(uploadPath.toFile.getAbsolutePath)
    for{records<-recordFuture
        cdxConfig <- sysConfigDB.getCdxConfig()
        } yield {
      records.foreach(record=>cdxUploader.upload(record, uploadPath, cdxConfig))
      Ok("")
    }
  }
}
