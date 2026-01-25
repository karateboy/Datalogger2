package models

case class ModelConfig(model: String, monitorTypeIDs: List[String])
case class ValueReg(addr: Int, desc: String, unit: String)
case class ModeReg(addr: Int, desc: String)
case class ModelReg(inputRegs: List[ValueReg],
                    holdingRegs: List[ValueReg],
                    modeRegs: List[ModeReg],
                    warnRegs: List[ModeReg],
                    coilRegs: List[ModeReg])

case class ModelRegValue(inputRegs: List[(InstrumentStatusType, Float)],
                         holdingRegs: List[(InstrumentStatusType, Float)],
                         modeRegs: List[(InstrumentStatusType, Boolean)],
                         warnRegs: List[(InstrumentStatusType, Boolean)])