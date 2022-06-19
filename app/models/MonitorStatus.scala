package models

object StatusType extends Enumeration {
  val Internal = Value("0")
  val Auto = Value("A")
  val ManualInvalid = Value("M")
  val ManualValid = Value("m")
  def map = Map(Internal -> "系統",
    Auto -> "自動註記",
    ManualInvalid -> "人工註記:無效資料",
    ManualValid -> "人工註記:有效資料"
  )
}

case class MonitorStatus(_id: String, desp: String) {
  val info: TagInfo = MonitorStatus.getTagInfo(_id)
}

case class TagInfo(statusType: StatusType.Value, auditRule: Option[Char], id: String) {
  override def toString = {
    if ((statusType != StatusType.Internal)
      && auditRule.isDefined)
      auditRule.get + id
    else
      statusType + id
  }
}

object MonitorStatus {
  val NormalStat = "010"
  val OverNormalStat = "011"
  val BelowNormalStat = "012"
  val ZeroCalibrationStat = "020"
  val SpanCalibrationStat = "021"
  val CalibrationDeviation = "022"
  val CalibrationResume = "026"
  val InvalidDataStat = "030"
  val MaintainStat = "031"
  val ExceedRangeStat = "032"

  def getTagInfo(tag: String) = {
    val id = tag.substring(1)
    val t = tag.charAt(0)
    t match {
      case '0' =>
        TagInfo(StatusType.Internal, None, id)
      case 'm' =>
        TagInfo(StatusType.ManualValid, Some(t), id)
      case 'M' =>
        TagInfo(StatusType.ManualInvalid, Some(t), id)
      case l =>
        if (t.isLetter)
          TagInfo(StatusType.Auto, Some(t), id)
        else
          throw new Exception("Unknown type:" + t)
    }
  }

  def getCssClassStr(tag: String, overInternal: Boolean = false, overLaw: Boolean = false) = {
    val info = getTagInfo(tag)
    val statClass =
      info.statusType match {
        case StatusType.Internal =>
          if (isValid(tag))
            ""
          else if (isCalbration(tag))
            "calibration_status"
          else if (isMaintenance(tag))
            "maintain_status"
          else
            "abnormal_status"

        case StatusType.Auto =>
          "auto_audit_status"
        case StatusType.ManualInvalid =>
          "manual_audit_status"
        case StatusType.ManualValid =>
          "manual_audit_status"
      }

    val fgClass =
      if (overLaw)
        "over_law_std"
      else if (overInternal)
        "over_internal_std"
      else
        "normal"

    if(statClass != "")
      Seq(statClass, fgClass)
    else
      Seq(fgClass)
  }

  def switchTagToInternal(tag: String) = {
    val info = getTagInfo(tag)
    '0' + info.id
  }

  def isValid(s: String) = {
    val tagInfo = getTagInfo(s)
    val VALID_STATS = List(NormalStat, OverNormalStat, BelowNormalStat).map(getTagInfo)

    tagInfo.statusType match {
      case StatusType.Internal =>
        VALID_STATS.contains(getTagInfo(s))
      case StatusType.Auto =>
        if (tagInfo.auditRule.isDefined && tagInfo.auditRule.get.isLower)
          true
        else
          false
      case StatusType.ManualValid =>
          true
      case StatusType.ManualInvalid =>
        false
    }
  }

  def isCalbration(s: String) = {
    val CALBRATION_STATS = List(ZeroCalibrationStat, SpanCalibrationStat,
      CalibrationDeviation,CalibrationResume).map(getTagInfo)

    CALBRATION_STATS.contains(getTagInfo(s))
  }

  def isMaintenance(s: String) = {
    getTagInfo(MaintainStat) == getTagInfo(s)
  }

  def isManual(s:String) = {
    getTagInfo(s).statusType == StatusType.ManualInvalid || getTagInfo(s).statusType == StatusType.ManualInvalid
  }
  def isError(s: String) = {
    !(isValid(s) || isCalbration(s) || isMaintenance(s))
  }
}

