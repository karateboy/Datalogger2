package models.sql

import com.github.nscala_time.time.Imports
import models.{Calibration, CalibrationDB, MonitorTypeDB}

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

@Singleton
class CalibrationOp @Inject()() extends CalibrationDB{
  override def calibrationReport(start: Imports.DateTime, end: Imports.DateTime): Seq[Calibration] = ???

  override def calibrationReportFuture(start: Imports.DateTime, end: Imports.DateTime): Future[Seq[Calibration]] = ???

  override def calibrationReportFuture(start: Imports.DateTime): Future[Seq[Calibration]] = ???

  override def calibrationReport(mt: String, start: Imports.DateTime, end: Imports.DateTime): Seq[Calibration] = ???

  override def getCalibrationMap(startDate: Imports.DateTime, endDate: Imports.DateTime)(implicit monitorTypeOp: MonitorTypeDB): Future[Map[String, List[(Imports.DateTime, Calibration)]]] = ???

  override def insert(cal: Calibration): Unit = ???
}
