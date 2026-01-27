package models

case class ModelConfig(model: String, monitorTypeIDs: List[String])
case class ValueReg(addr: Int, desc: String, unit: String)
case class ModeReg(addr: Int, desc: String)
case class ModelReg(inputRegs: List[ValueReg],
                    holdingRegs: List[ValueReg],
                    modeRegs: List[ModeReg],
                    warnRegs: List[ModeReg],
                    coilRegs: List[ModeReg])

case class ModelRegValue(inputs: List[(InstrumentStatusType, Float)],
                         holdings: List[(InstrumentStatusType, Float)],
                         modes: List[(InstrumentStatusType, Boolean)],
                         warnings: List[(InstrumentStatusType, Boolean)],
                         input64s: List[(InstrumentStatusType, Double)] = List.empty,
                         holding64s: List[(InstrumentStatusType, Double)] = List.empty)

case class RegDouble(ist:InstrumentStatusType, value:Double)
case class RegBool(ist:InstrumentStatusType, value:Boolean)
case class RegValueSet(inputs: List[RegDouble],
                         holdings: List[RegDouble],
                         modes: List[RegBool],
                         warnings: List[RegBool],
                         input64s: List[RegDouble] = List.empty,
                         holding64s: List[RegDouble] = List.empty){
  def dataList: List[RegDouble] = inputs ++ holdings ++ input64s ++ holding64s
}