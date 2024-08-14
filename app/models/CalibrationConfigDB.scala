package models

import play.api.libs.json.{Json, OWrites, Reads}

import java.time.LocalTime
import scala.concurrent.Future

case class PointCalibrationConfig(enable: Boolean,
                                  name: String,
                                  raiseTime: Int,
                                  holdTime: Int,
                                  calibrateSeq: Option[String] = None,
                                  calibrateDO: Option[Int] = None,
                                  skipInternalVault: Option[Boolean] = None,
                                  fullSpanPercent: Option[Double] = None,
                                  deviationAllowance: Option[Double] = None)

case class CalibrationConfig(_id: String,
                             instrumentIds: Seq[String],
                             calibrationTime: Option[String],
                             pointConfigs: Seq[PointCalibrationConfig]){
  def getCalibrationTime: Option[LocalTime] = calibrationTime.map({ timeStr =>
    val parser = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
    java.time.LocalTime.parse(timeStr, parser)
  })
}

trait CalibrationConfigDB {
  implicit val pointCalibrationConfigRead: Reads[PointCalibrationConfig] = Json.reads[PointCalibrationConfig]
  implicit val pointCalibrationConfigWrite: OWrites[PointCalibrationConfig] = Json.writes[PointCalibrationConfig]
  implicit val calibrationConfigRead: Reads[CalibrationConfig] = Json.reads[CalibrationConfig]
  implicit val calibrationConfigWrite: OWrites[CalibrationConfig] = Json.writes[CalibrationConfig]

  def upsertFuture(calibrationConfig: CalibrationConfig): Future[Boolean]

  def getListFuture: Future[Seq[CalibrationConfig]]

  def deleteFuture(name: String): Future[Boolean]

}
