package controllers

import com.github.nscala_time.time.Imports._
import models._
import play.api._
import play.api.data.Forms._
import play.api.data._
import play.api.libs.json._
import play.api.mvc._

import javax.inject._
import scala.concurrent.Future

class HomeController @Inject() (environment: play.api.Environment, recordOp: RecordOp,
                             userOp: UserOp, instrumentOp: InstrumentOp, dataCollectManagerOp: DataCollectManagerOp,
                             monitorTypeOp: MonitorTypeOp, query: Query,
                             instrumentTypeOp: InstrumentTypeOp, monitorStatusOp: MonitorStatusOp) extends Controller {

  val title = "資料擷取器"

  val epaReportPath: String = environment.rootPath + "/importEPA/"

  def importEpa = Action {
    def listExcels(dir: String) = {
      new java.io.File(dir).listFiles.filter(_.getName.endsWith(".xlsx"))
    }
    val msgBuffer = new StringBuffer

    import java.io.File
    def importEpaData(f: File) {
      import org.apache.poi.ss.usermodel._

      import java.io.FileInputStream

      val wb = WorkbookFactory.create(new FileInputStream(f));
      val sheet = wb.getSheetAt(0)
      val mtStr = sheet.getRow(1).getCell(2).getStringCellValue
      try {
        (mtStr)
      } catch {
        case _: Throwable =>
          val msg = "未知的測項代碼:" + mtStr
          msgBuffer.append(msg)
          throw new Exception(msg)
      }

      import scala.collection.mutable.Queue
      val updateQueue = Queue.empty[(DateTime, Double)]

      var rowN = 2
      var row = sheet.getRow(rowN)
      while (row != null) {
        val timeStr = row.getCell(0).getStringCellValue
        val time = DateTime.parse(timeStr, DateTimeFormat.forPattern("YYYY/MM/dd hh:mm:ss"))

        val mtValue = row.getCell(1).getNumericCellValue
        updateQueue.enqueue((time, mtValue))
        rowN += 1
        row = sheet.getRow(rowN)
      }
      msgBuffer.append("共" + updateQueue.length + "筆資料\n")
      wb.close()

      {
        val f = recordOp.updateMtRecord(recordOp.SecCollection)((mtStr), updateQueue)
        import models.ModelHelper._
        val ret = waitReadyResult(f)
      }
    }

    try {
      for (f <- listExcels(epaReportPath)) {
        msgBuffer.append("匯入:" + f.getName + "\n")
        importEpaData(f)
        f.delete()
      }
      Ok(msgBuffer.toString())
    } catch {
      case ex: Throwable =>
        Ok("匯入失敗:" + ex.getMessage)
    }
  }

  implicit val userParamRead: Reads[User] = Json.reads[User]

  def newUser = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      val newUserParam = request.body.validate[User]

      newUserParam.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        param => {
          userOp.newUser(param)
          Ok(Json.obj("ok" -> true))
        })
  }

  def deleteUser(email: String) = Security.Authenticated {
    implicit request =>
      val userInfoOpt = Security.getUserinfo(request)
      val userInfo = userInfoOpt.get

      userOp.deleteUser(email)
      Ok(Json.obj("ok" -> true))
  }

  def updateUser(id: String) = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      val userParam = request.body.validate[User]

      userParam.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        param => {
          userOp.updateUser(param)
          Ok(Json.obj("ok" -> true))
        })
  }

  def getAllUsers = Security.Authenticated {
    val users = userOp.getAllUsers()
    implicit val userWrites = Json.writes[User]

    Ok(Json.toJson(users))
  }

  case class EditData(id: String, data: String)
  def saveMonitorTypeConfig() = Security.Authenticated {
    implicit request =>
      try {
        val mtForm = Form(
          mapping(
            "id" -> text,
            "data" -> text)(EditData.apply)(EditData.unapply))

        val mtData = mtForm.bindFromRequest.get
        val mtInfo = mtData.id.split(":")
        val mt = (mtInfo(0))

        monitorTypeOp.updateMonitorType(mt, mtInfo(1), mtData.data)

        Ok(mtData.data)
      } catch {
        case ex: Throwable =>
          Logger.error(ex.getMessage, ex)
          BadRequest(ex.toString)
      }
  }

  def getInstrumentTypes = Security.Authenticated {
    implicit val w1 = Json.writes[ProtocolInfo]
    implicit val write = Json.writes[InstrumentTypeInfo]
    val iTypes = instrumentTypeOp.map.values.map  { t =>
      InstrumentTypeInfo(t.id, t.desp,
        t.protocol.map { p => ProtocolInfo(p, Protocol.map(p)) })
    }
    Ok(Json.toJson(iTypes.toList))
  }

  def getInstrumentType(id: String) = Security.Authenticated {
    implicit val w1 = Json.writes[ProtocolInfo]
    implicit val write = Json.writes[InstrumentTypeInfo]
    val iTypes = instrumentTypeOp.map.values.filter(t=>t.id == id) map  { t =>
      InstrumentTypeInfo(t.id, t.desp,
        t.protocol.map { p => ProtocolInfo(p, Protocol.map(p)) })
    }
    Ok(Json.toJson(iTypes))
  }

  implicit val w1 = Json.writes[InstrumentStatusType]
  implicit val w = Json.writes[Instrument]
  def newInstrument = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      implicit val r1 = Json.reads[InstrumentStatusType]
      implicit val reads = Json.reads[Instrument]
      val instrumentResult = request.body.validate[Instrument]

      instrumentResult.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        rawInstrument => {
          try {
            val instType = instrumentTypeOp.map(rawInstrument.instType)
            val instParam = instType.driver.verifyParam(rawInstrument.param)
            val newInstrument = rawInstrument.replaceParam(instParam)
            if (newInstrument._id.isEmpty())
              throw new Exception("儀器ID不可是空的!")

            instrumentOp.upsertInstrument(newInstrument)

            //Stop measuring if any
            dataCollectManagerOp.stopCollect(newInstrument._id)
            monitorTypeOp.stopMeasuring(newInstrument._id)

            val mtList = instType.driver.getMonitorTypes(instParam)
            for (mt <- mtList) {
              monitorTypeOp.addMeasuring(mt, newInstrument._id, instType.analog)
            }
            dataCollectManagerOp.startCollect(newInstrument)
            Ok(Json.obj("ok" -> true))
          } catch {
            case ex: Throwable =>
              ModelHelper.logException(ex)
              Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
          }
        })
  }

  def getInstrumentList = Security.Authenticated {
    implicit val write = Json.writes[InstrumentInfo]
    val ret = instrumentOp.getInstrumentList()

    val ret2 = ret.map { inst =>
      def getMonitorTypes: List[String] = {
        val instTypeCase = instrumentTypeOp.map(inst.instType)
        instTypeCase.driver.getMonitorTypes(inst.param)
      }

      def getStateStr = {
        if (inst.active){
          monitorStatusOp.map(inst.state).desp
        }else
          "停用"
      }

      def getCalibrationTime = {
        val instTypeCase = instrumentTypeOp.map(inst.instType)
        instTypeCase.driver.getCalibrationTime(inst.param)
      }

      def getInfoClass = {
        val mtStr = getMonitorTypes.map { monitorTypeOp.map(_).desp }.mkString(",")
        val protocolParam =
          inst.protocol.protocol match {
            case Protocol.tcp =>
              inst.protocol.host.get
            case Protocol.serial =>
              s"COM${inst.protocol.comPort.get}"
          }
        val calibrationTime = getCalibrationTime.map { t => t.toString("HH:mm") }

        val state = getStateStr

        InstrumentInfo(inst._id, instrumentTypeOp.map(inst.instType).desp, state,
          Protocol.map(inst.protocol.protocol), protocolParam, mtStr, calibrationTime)
      }
      getInfoClass }
    Ok(Json.toJson(ret2))
  }

  def getInstrument(id: String) = Security.Authenticated {
    implicit val w = Json.writes[Instrument]
    val ret = instrumentOp.getInstrument(id)
    if (ret.isEmpty)
      BadRequest(s"No such instrument: $id")
    else {
      val inst = ret(0)
      Ok(Json.toJson(inst))
    }
  }

  def removeInstrument(instruments: String) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.foreach { dataCollectManagerOp.stopCollect(_) }
      ids.foreach { monitorTypeOp.stopMeasuring }
      ids.map { instrumentOp.delete }
    } catch {
      case ex: Exception =>
        Logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def deactivateInstrument(instruments: String) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.foreach { dataCollectManagerOp.stopCollect(_) }
      ids.map { instrumentOp.deactivate }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def activateInstrument(instruments: String) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      val f = ids.map { instrumentOp.activate }
      ids.foreach { dataCollectManagerOp.startCollect(_) }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def toggleMaintainInstrument(instruments: String) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.map { id =>
        instrumentOp.getInstrument(id).map { inst =>
          val newState =
            if (inst.state == MonitorStatus.MaintainStat)
              MonitorStatus.NormalStat
            else
              MonitorStatus.MaintainStat

          dataCollectManagerOp.setInstrumentState(id, newState)
        }
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def calibrateInstrument(instruments: String, zeroCalibrationStr: String) = Security.Authenticated {
    val ids = instruments.split(",")
    val zeroCalibration = zeroCalibrationStr.toBoolean
    Logger.debug(s"zeroCalibration=$zeroCalibration")

    try {
      ids.foreach { id =>
        if (zeroCalibration)
          dataCollectManagerOp.zeroCalibration(id)
        else
          dataCollectManagerOp.spanCalibration(id)
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def calibrateInstrumentFull(instruments: String) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.foreach { id =>
        dataCollectManagerOp.autoCalibration(id)
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def resetInstrument(instruments: String) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.map { id =>
        dataCollectManagerOp.setInstrumentState(id, MonitorStatus.NormalStat)
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def getExecuteSeq(instruments: String, seq: Int) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.map { id =>
        dataCollectManagerOp.executeSeq(seq)
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(ex.getMessage)
    }

    Ok(s"Execute $instruments $seq")
  }

  def executeSeq(instruments: String, seq: Int) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.map { id =>
        dataCollectManagerOp.executeSeq(seq)
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def monitorTypeList = Security.Authenticated {
    implicit val writes = Json.writes[MonitorType]
    val mtList = monitorTypeOp.mtvList map monitorTypeOp.map
    Ok(Json.toJson(mtList))
  }

  def upsertMonitorType(id:String) = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      Logger.info(s"upsert Mt:${id}")
      implicit val read = Json.reads[MonitorType]
      val mtResult = request.body.validate[MonitorType]

      mtResult.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        mt => {
          monitorTypeOp.upsertMonitorType(mt)
          Ok(Json.obj("ok" -> true))
        })
  }


  def recalculateHour(startStr: String, endStr: String) = Security.Authenticated {
    val start = DateTime.parse(startStr, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm"))
    val end = DateTime.parse(endStr, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm"))

    for (hour <- query.getPeriods(start, end, 1.hour)) {
      dataCollectManagerOp.recalculateHourData(hour, false)(monitorTypeOp.mtvList)
    }
    Ok(Json.obj("ok" -> true))
  }

  def uploadData(tabStr: String, startStr: String, endStr: String) = Security.Authenticated {
    val tab = TableType.withName(tabStr)
    val start = DateTime.parse(startStr, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm"))
    val end = DateTime.parse(endStr, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm"))

    tab match {
      case TableType.min =>
        ForwardManager.forwardMinRecord(start, end)
      case TableType.hour =>
        ForwardManager.forwardHourRecord(start, end)
    }

    Ok(Json.obj("ok" -> true))
  }

  def testEvtOptHigh = Security.Authenticated {
    dataCollectManagerOp.evtOperationHighThreshold
    Ok("ok")
  }
}
