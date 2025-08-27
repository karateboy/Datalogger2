package models

import com.github.nscala_time.time.Imports._
import controllers.DisplayReport
import controllers.Highchart.HighchartData
import org.apache.poi.openxml4j.opc._
import org.apache.poi.ss.usermodel._
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel._

import java.io._
import java.math.MathContext
import java.nio.file._
import javax.inject._
import scala.collection.JavaConverters.asScalaIteratorConverter
import scala.math.BigDecimal.RoundingMode

@Singleton
class ExcelUtility @Inject()
(environment: play.api.Environment, monitorTypeOp: MonitorTypeDB, monitorStatusOp: MonitorStatusDB) {
  val docRoot = environment.rootPath + "/report_template/"

  def exportChartData(chart: HighchartData, monitorTypes: Array[String], showSec: Boolean): File = {
    val precArray = monitorTypes.map { mt =>
      if (monitorTypeOp.map.contains(mt))
        monitorTypeOp.map(mt).prec
      else {
        val realType = MonitorType.getRealType(mt)
        monitorTypeOp.map(realType).prec
      }
    }
    exportChartData(chart, precArray, showSec)
  }

  def exportChartData(chart: HighchartData, precision: Array[Int], showSec: Boolean) = {
    val (reportFilePath, pkg, wb) = prepareTemplate("chart_export.xlsx")
    val evaluator = wb.getCreationHelper().createFormulaEvaluator()
    val format = wb.createDataFormat();

    val sheet = wb.getSheetAt(0)
    val headerRow = sheet.createRow(0)
    headerRow.createCell(0).setCellValue("時間")
    val calibrationStyle = wb.createCellStyle()
    calibrationStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex)
    calibrationStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND)
    val manualStyle = wb.createCellStyle()
    manualStyle.setFillForegroundColor(IndexedColors.GOLD.getIndex)
    manualStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND)
    val maintenanceStyle = wb.createCellStyle()
    maintenanceStyle.setFillForegroundColor(IndexedColors.LAVENDER.getIndex)
    maintenanceStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND)

    for ((series, colIdx) <- chart.series.zipWithIndex) {
      headerRow.createCell(1 + 2 * colIdx).setCellValue(series.name)
      if (series.statusList.nonEmpty)
        headerRow.createCell(1 + 2 * colIdx + 1).setCellValue("狀態碼")
    }

    val styles = precision.map { prec =>
      val format_str: String = if (prec != 0)
        "0." + "0" * prec
      else
        "0"

      val style = wb.createCellStyle();
      style.setDataFormat(format.getFormat(format_str))
      style
    }

    // Categories data
    if (chart.xAxis.categories.isDefined) {
      val timeList = chart.xAxis.categories.get
      for ((timeStr, rowIdx) <- timeList.zipWithIndex) {
        val thisRow = sheet.createRow(rowIdx + 1)
        thisRow.createCell(0).setCellValue(timeStr)

        for ((series, colIdx) <- chart.series.zipWithIndex) {
          val cell = thisRow.createCell(1 + colIdx * 2)
          cell.setCellStyle(styles(colIdx))
          val pair = series.data(rowIdx)
          val statusOpt = series.statusList(rowIdx)
          for (v <- pair._2 if !v.isNaN) {
            val d = BigDecimal(v).setScale(precision(colIdx), RoundingMode.HALF_EVEN)
            cell.setCellValue(d.doubleValue())
          }
          for (status <- statusOpt) {
            val statusCell = thisRow.createCell(1 + colIdx * 2 + 1)
            statusCell.setCellValue(status)
          }
        }
      }
    } else {
      val rowMax = chart.series.map(s => s.data.length).max
      for (row <- 1 to rowMax) {
        val thisRow = sheet.createRow(row)
        for {
          (series, colIdx) <- chart.series.zipWithIndex
        } {
          val pair = series.data(row - 1)
          if (colIdx == 0) {
            val timeCell = thisRow.createCell(0)
            val dt = new DateTime(pair._1)
            if (!showSec)
              timeCell.setCellValue(dt.toString("YYYY/MM/dd HH:mm"))
            else
              timeCell.setCellValue(dt.toString("YYYY/MM/dd HH:mm:ss"))
          }

          val cell = thisRow.createCell(colIdx * 2 + 1)
          cell.setCellStyle(styles(colIdx))
          for (v <- pair._2 if !v.isNaN) {
            val d = BigDecimal(v).setScale(precision(colIdx), RoundingMode.HALF_EVEN)
            cell.setCellValue(d.doubleValue())
            if (series.statusList.nonEmpty)
              for (status <- series.statusList(row - 1)) {
                val tagInfo = MonitorStatus.getTagInfo(status)
                val statusCell = thisRow.createCell(2 * colIdx + 2)
                val monitorStatus = monitorStatusOp.map(status)
                statusCell.setCellValue(monitorStatus.desp)
                if (MonitorStatus.isCalibration(status)) {
                  cell.setCellStyle(calibrationStyle)
                  statusCell.setCellStyle(calibrationStyle)
                } else if (tagInfo.statusType == StatusType.ManualValid ||
                  tagInfo.statusType == StatusType.ManualInvalid) {
                  cell.setCellStyle(manualStyle)
                  statusCell.setCellStyle(manualStyle)
                } else if (MonitorStatus.isMaintenance(status)) {
                  cell.setCellStyle(maintenanceStyle)
                  statusCell.setCellStyle(maintenanceStyle)
                }
              }
          }
        }
      }
    }

    finishExcel(reportFilePath, pkg, wb)
  }

  def createStyle(prec: Int)(implicit wb: XSSFWorkbook) = {
    val format_str = if (prec != 0)
      "0." + "0" * prec
    else
      "0"

    val style = wb.createCellStyle();
    val format = wb.createDataFormat();
    val font = wb.createFont();
    font.setFontHeightInPoints(12);
    font.setFontName("正黑體");

    style.setFont(font)
    style.setDataFormat(format.getFormat(format_str))
    style.setBorderBottom(BorderStyle.THIN);
    style.setBottomBorderColor(IndexedColors.BLACK.getIndex());
    style.setBorderLeft(BorderStyle.THIN);
    style.setLeftBorderColor(IndexedColors.BLACK.getIndex());
    style.setBorderRight(BorderStyle.THIN);
    style.setRightBorderColor(IndexedColors.BLACK.getIndex());
    style.setBorderTop(BorderStyle.THIN);
    style.setTopBorderColor(IndexedColors.BLACK.getIndex());
    style
  }

  def getStyle(tag: String, normalStyle: XSSFCellStyle, abnormalStyles: Array[XSSFCellStyle]): XSSFCellStyle = {
    import MonitorStatus._
    MonitorStatus.getTagInfo(tag)
    if (isValid(tag))
      normalStyle
    else if (isCalibration(tag))
      abnormalStyles(0)
    else if (MonitorStatus.isMaintenance(tag))
      abnormalStyles(1)
    else if (MonitorStatus.isManual(tag))
      abnormalStyles(3)
    else
      abnormalStyles(2)
  }

  def exportDailyReport(dateTime: DateTime, dailyReport: DisplayReport) = {
    val (reportFilePath, pkg, wb) = prepareTemplate("dailyReport.xlsx")
    val evaluator = wb.getCreationHelper().createFormulaEvaluator()
    val format = wb.createDataFormat()
    val sheet = wb.getSheetAt(0)
    val titleRow = sheet.createRow(0)
    for (i <- 0 to dailyReport.columnNames.size + 1)
      titleRow.createCell(i)

    val statusStyle =
      for (col <- 0 to 4)
        yield sheet.getRow(1).getCell(col).getCellStyle

    sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, dailyReport.columnNames.size))
    titleRow.getCell(0).setCellValue(s"監測日報 ${dateTime.toString("YYYY年MM月dd日")}")

    def setValue(cell: Cell, v: String): Unit = {
      try {
        val d = BigDecimal(v, MathContext.DECIMAL32)
        cell.setCellValue(d.doubleValue())
      } catch {
        case _: Throwable =>
          cell.setCellValue(v)
      }
    }

    val headerRow = sheet.getRow(2)
    for ((mtName, mtIdx) <- dailyReport.columnNames.zipWithIndex) {
      val headerCell = headerRow.createCell(mtIdx + 1)
      headerCell.setCellValue(mtName)
      headerCell.setCellStyle(statusStyle(0))
      for (hr <- 0 to 23) {
        val row = sheet.getRow(3 + hr)
        val cell = row.createCell(mtIdx + 1)
        val mtHrData = dailyReport.rows(hr).cellData(mtIdx)
        setValue(cell, mtHrData.v)
        var hasStyle = false
        mtHrData.cellClassName.foreach(status => {
          status match {
            case "calibration_status" =>
              hasStyle = true
              cell.setCellStyle(statusStyle(1))
            case "maintain_status" =>
              hasStyle = true
              cell.setCellStyle(statusStyle(2))
            case "abnormal_status" =>
              hasStyle = true
              cell.setCellStyle(statusStyle(3))
            case "manual_audit_status" =>
              hasStyle = true
              cell.setCellStyle(statusStyle(4))
            case "normal" =>
              if (!hasStyle)
                cell.setCellStyle(statusStyle(0))
            case _ =>
              if (!hasStyle)
                cell.setCellStyle(statusStyle(0))
          }
        })
      }
    }

    for ((statusRowData, statusIdx) <- dailyReport.statRows.zipWithIndex) {
      val row = sheet.createRow(statusIdx + 3 + dailyReport.rows.size)
      val titleCell = row.createCell(0)
      titleCell.setCellValue(statusRowData.name)
      titleCell.setCellStyle(statusStyle(0))
      for (mtIdx <- 0 to dailyReport.columnNames.size - 1) {
        val valueCell = row.createCell(mtIdx + 1)
        setValue(valueCell, statusRowData.cellData(mtIdx).v)
        valueCell.setCellStyle(statusStyle(0))
      }
    }

    finishExcel(reportFilePath, pkg, wb)
  }

  def exportDisplayReport(title: String, displayReport: DisplayReport) = {
    val (reportFilePath, pkg, wb) = prepareTemplate("monthlyReport.xlsx")
    val evaluator = wb.getCreationHelper().createFormulaEvaluator()
    val format = wb.createDataFormat()
    val sheet = wb.getSheetAt(0)
    val titleRow = sheet.createRow(0)
    for (i <- 0 to displayReport.columnNames.size + 1)
      titleRow.createCell(i)

    val statusStyle =
      for (col <- 0 to 4)
        yield sheet.getRow(1).getCell(col).getCellStyle

    sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, displayReport.columnNames.size))
    titleRow.getCell(0).setCellValue(title)

    def setValue(cell: Cell, v: String): Unit = {
      try {
        val d = BigDecimal(v, MathContext.DECIMAL32)
        cell.setCellValue(d.doubleValue())
      } catch {
        case _: Throwable =>
          cell.setCellValue(v)
      }
    }

    val headerRow = sheet.getRow(2)
    for ((mtName, mtIdx) <- displayReport.columnNames.zipWithIndex) {
      val headerCell = headerRow.createCell(mtIdx + 1)
      headerCell.setCellValue(mtName)
      headerCell.setCellStyle(statusStyle(0))
      for (rowIdx <- 0 to displayReport.rows.size - 1) {
        val row = sheet.getRow(3 + rowIdx)
        val dateTime = new DateTime(displayReport.rows(rowIdx).date)
        val dateTimeCell = row.createCell(0)
        dateTimeCell.setCellValue(dateTime.toString("M/dd"))
        dateTimeCell.setCellStyle(statusStyle(0))
        val cell = row.createCell(mtIdx + 1)
        val mtHrData = displayReport.rows(rowIdx).cellData(mtIdx)
        setValue(cell, mtHrData.v)

        var hasStyle = false
        mtHrData.cellClassName.foreach(status => {
          status match {
            case "calibration_status" =>
              hasStyle = true
              cell.setCellStyle(statusStyle(1))
            case "maintain_status" =>
              hasStyle = true
              cell.setCellStyle(statusStyle(2))
            case "abnormal_status" =>
              hasStyle = true
              cell.setCellStyle(statusStyle(3))
            case "manual_audit_status" =>
              hasStyle = true
              cell.setCellStyle(statusStyle(4))
            case "normal" =>
              if (!hasStyle)
                cell.setCellStyle(statusStyle(0))
            case _ =>
          }
        })
        if (!hasStyle)
          cell.setCellStyle(statusStyle(0))
      }
    }

    for ((statusRowData, statusIdx) <- displayReport.statRows.zipWithIndex) {
      val row = sheet.createRow(statusIdx + 3 + displayReport.rows.size)
      val titleCell = row.createCell(0)
      titleCell.setCellValue(statusRowData.name)
      titleCell.setCellStyle(statusStyle(0))
      for (mtIdx <- 0 to displayReport.columnNames.size - 1) {
        if (mtIdx < statusRowData.cellData.length) {
          val valueCell = row.createCell(mtIdx + 1)
          setValue(valueCell, statusRowData.cellData(mtIdx).v)
          valueCell.setCellStyle(statusStyle(0))
        }
      }
    }
    finishExcel(reportFilePath, pkg, wb)
  }

  private def prepareTemplate(templateFile: String) = {
    val templatePath = Paths.get(docRoot + templateFile)
    val reportFilePath = Files.createTempFile("temp", ".xlsx");

    Files.copy(templatePath, reportFilePath, StandardCopyOption.REPLACE_EXISTING)

    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(reportFilePath.toAbsolutePath().toString()))
    val wb = new XSSFWorkbook(pkg);

    (reportFilePath, pkg, wb)
  }

  def finishExcel(reportFilePath: Path, pkg: OPCPackage, wb: XSSFWorkbook) = {
    val out = new FileOutputStream(reportFilePath.toAbsolutePath().toString());
    wb.write(out);
    out.close();
    pkg.close();

    new File(reportFilePath.toAbsolutePath().toString())
  }

  def calibrationReport(start: DateTime, end: DateTime, calibrationList: Seq[Calibration]): File = {
    val (reportFilePath, pkg, wb) = prepareTemplate("calibrationReport.xlsx")
    val evaluator = wb.getCreationHelper().createFormulaEvaluator()

    val sheet = wb.getSheetAt(0)
    sheet.getRow(0).getCell(0).setCellValue(s"資料日期:${start.toString("YYYY/MM/dd")}~${end.toString("YYYY/MM/dd")}")

    val normalStyle = sheet.getRow(1).getCell(10).getCellStyle
    val failedStyle = sheet.getRow(1).getCell(11).getCellStyle
    for ((calibration, idx) <- calibrationList.zipWithIndex) {
      val row = sheet.createRow(3 + idx)
      val mt = calibration.monitorType
      val mtCase = monitorTypeOp.map(mt)
      row.createCell(0).setCellValue(monitorTypeOp.map(calibration.monitorType).desp)
      row.createCell(1).setCellValue(new DateTime(calibration.startTime).toString("YYYY年MM月dd日 HH:mm"))
      row.createCell(2).setCellValue(new DateTime(calibration.endTime).toString("YYYY年MM月dd日 HH:mm"))
      row.createCell(3).setCellValue(monitorTypeOp.format(mt, calibration.zero_val))
      row.createCell(4).setCellValue(monitorTypeOp.format(mt, mtCase.zd_law))
      row.createCell(5).setCellValue(monitorTypeOp.format(mt, calibration.span_val))
      row.createCell(6).setCellValue(monitorTypeOp.format(mt, calibration.span_std))
      row.createCell(7).setCellValue(monitorTypeOp.format(mt, calibration.span_devOpt))
      row.createCell(8).setCellValue(monitorTypeOp.format(mt, mtCase.span_dev_law))
      val mOpt =
        for {span_val <- calibration.span_val; zero_val <- calibration.zero_val;
             span_std <- calibration.span_std if span_val - zero_val != 0} yield
          span_std / (span_val - zero_val)

      val mStr = mOpt.map(s"%.6f".format(_)).getOrElse("-")
      row.createCell(9).setCellValue(mStr)
      val bOpt =
        for {span_val <- calibration.span_val; zero_val <- calibration.zero_val;
             span_std <- calibration.span_std if span_val - zero_val != 0} yield
          (-zero_val * span_std) / (span_val - zero_val)

      val bStr = bOpt.map(s"%.6f".format(_)).getOrElse("-")
      row.createCell(10).setCellValue(bStr)
      val statusCell = row.createCell(11)
      if (calibration.success(monitorTypeOp)) {
        statusCell.setCellValue("成功")
        statusCell.setCellStyle(normalStyle)
      } else {
        statusCell.setCellValue("失敗")
        statusCell.setCellStyle(failedStyle)
      }
      for (i <- 0 to 10)
        row.getCell(i).setCellStyle(normalStyle)

    }
    finishExcel(reportFilePath, pkg, wb)
  }

  def multiCalibrationReport(start: DateTime, end: DateTime, calibrationList: Seq[Calibration]): File = {
    val (reportFilePath, pkg, wb) = prepareTemplate("multiCalibrationReport.xlsx")
    val evaluator = wb.getCreationHelper().createFormulaEvaluator()

    val sheet = wb.getSheetAt(0)
    sheet.getRow(0).getCell(0).setCellValue(s"資料日期:${start.toString("YYYY/MM/dd")}~${end.toString("YYYY/MM/dd")}")

    val normalStyle = sheet.getRow(1).getCell(9).getCellStyle
    val successStyle = sheet.getRow(1).getCell(10).getCellStyle
    val failedStyle = sheet.getRow(1).getCell(11).getCellStyle
    for ((calibration, idx) <- calibrationList.zipWithIndex) {
      val row = sheet.createRow(3 + idx)
      val mt = calibration.monitorType

      def setPointCell(cell: Cell, value: Option[Double], statusOpt: Option[Boolean]): Unit = {
        cell.setCellValue(monitorTypeOp.format(mt, value))
        statusOpt match {
          case Some(true) =>
            cell.setCellStyle(successStyle)
          case Some(false) =>
            cell.setCellStyle(failedStyle)
          case None =>
            cell.setCellStyle(normalStyle)
        }
      }

      row.createCell(0).setCellValue(monitorTypeOp.map(calibration.monitorType).desp)
      row.createCell(1).setCellValue(new DateTime(calibration.startTime).toString("YYYY年MM月dd日 HH:mm"))
      row.createCell(2).setCellValue(new DateTime(calibration.endTime).toString("YYYY年MM月dd日 HH:mm"))
      setPointCell(row.createCell(3), calibration.zero_val, calibration.zero_success)
      setPointCell(row.createCell(4), calibration.span_val, calibration.span_success)
      setPointCell(row.createCell(5), calibration.point3, calibration.point3_success)
      setPointCell(row.createCell(6), calibration.point4, calibration.point4_success)
      setPointCell(row.createCell(7), calibration.point5, calibration.point5_success)
      setPointCell(row.createCell(8), calibration.point6, calibration.point6_success)

      val mOpt =
        for {span_val <- calibration.span_val; zero_val <- calibration.zero_val;
             span_std <- calibration.span_std if span_val - zero_val != 0} yield
          span_std / (span_val - zero_val)

      val mStr = mOpt.map(s"%.6f".format(_)).getOrElse("-")
      val mCell = row.createCell(9)
      mCell.setCellValue(mStr)
      mCell.setCellStyle(normalStyle)

      val bOpt =
        for {span_val <- calibration.span_val; zero_val <- calibration.zero_val;
             span_std <- calibration.span_std if span_val - zero_val != 0} yield
          (-zero_val * span_std) / (span_val - zero_val)

      val bStr = bOpt.map(s"%.6f".format(_)).getOrElse("-")
      val bCell = row.createCell(10)
      bCell.setCellValue(bStr)
      bCell.setCellStyle(normalStyle)

      val statusCell = row.createCell(11)
      if (calibration.zero_success.getOrElse(true)
        && calibration.span_success.getOrElse(true)
        && calibration.point3_success.getOrElse(true)
        && calibration.point4_success.getOrElse(true)
        && calibration.point5_success.getOrElse(true)
        && calibration.point6_success.getOrElse(true)
      ) {
        statusCell.setCellValue("成功")
        statusCell.setCellStyle(successStyle)
      } else {
        statusCell.setCellValue("失敗")
        statusCell.setCellStyle(failedStyle)
      }

      for (i <- 0 to 2)
        row.getCell(i).setCellStyle(normalStyle)
    }
    finishExcel(reportFilePath, pkg, wb)
  }

  def getUpsertMinData(file: File): Seq[Map[String, Any]] = {
    val wb = new XSSFWorkbook(new FileInputStream(file))
    val sheet = wb.getSheetAt(0)
    val rowIterator = sheet.rowIterator().asScala
    rowIterator.next()
    rowIterator.next()
    val header = rowIterator.next().cellIterator().asScala.map { cell =>
      cell.getStringCellValue
    }.toSeq

    val data = rowIterator map { row: Row =>
      val cells = row.cellIterator().asScala.toList
      val rowMap = header.zip(cells).flatMap { case (key, cell) =>
        try {
          val value = key match {
            case "時間" =>
              try {
                cell.getDateCellValue
              } catch {
                case _: Throwable =>
                  try {
                    DateTime.parse(cell.getStringCellValue, DateTimeFormat.forPattern("YYYY/MM/dd HH:mm:ss")).toDate
                  } catch {
                    case _: Throwable =>
                      DateTime.parse(cell.getStringCellValue, DateTimeFormat.forPattern("YYYY/MM/dd HH:mm")).toDate
                  }
              }

            case _ =>
              cell.getNumericCellValue
          }
          Some(key -> value)
        } catch {
          case _: Throwable =>
            None
        }
      }.toMap
      rowMap
    }
    data.toSeq
  }

  def getUpsertMDL(file: File): Seq[(String, Double)] = {
    val wb = new XSSFWorkbook(new FileInputStream(file))
    val sheet = wb.getSheetAt(0)
    val rowIterator = sheet.rowIterator().asScala
    rowIterator.next()
    rowIterator.next()
    rowIterator.next()

    val data = rowIterator map { row: Row =>
      val cells = row.cellIterator().asScala.toList
      if (cells.size < 13)
        None
      else {
        try{
          val mt = cells(1).getStringCellValue
          val value = cells(12).getNumericCellValue
          Some((mt, value))
        }catch {
          case _: Throwable =>
            None
        }

      }
    }
    wb.close()
    data.flatten.toSeq
  }
}