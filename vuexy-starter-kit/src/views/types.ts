/* eslint-disable camelcase */
export interface Sensor {
  id: string;
  topic: string;
  monitor: string;
  group: string;
}

export interface Ability {
  action: string;
  subject: string;
}

export interface Group {
  _id: string;
  name: string;
  monitors: Array<string>;
  monitorTypes: Array<string>;
  admin: boolean;
  abilities: Array<Ability>;
  parent: undefined | string;
}

export interface TextStrValue {
  text: string;
  value: string | number;
}

export interface MonitorTypeStatus {
  _id: string;
  desp: string;
  value: string;
  unit: string;
  instrument: string;
  status: string;
  classStr: Array<string>;
  order: number;
}

export interface ThresholdConfig {
  elapseTime: number;
}

export interface MonitorType {
  _id: string;
  desp: string;
  unit: string;
  prec: number;
  order: number;
  signalType: boolean;
  std_law?: number;
  zd_law?: number;
  span?: number;
  span_dev_law?: number;
  measuringBy?: Array<string>;
  acoustic?: boolean;
  spectrum?: boolean;
  levels?: Array<number>;
  calibrate?: boolean;
  accumulated?: boolean;
  fixedM?: number;
  fixedB?: number;
  overLawSignalType?: string;
}

export interface CellData {
  v: string;
  cellClassName: Array<string>;
  status?: string;
}

export interface RowData {
  date: number;
  cellData: Array<CellData>;
}

export interface StatRow {
  name: string;
  cellData: Array<CellData>;
}

export interface DisplayReport {
  columnNames: Array<String>;
  rows: Array<RowData>;
  statRows: Array<StatRow>;
}

export interface CalibrationConfig {
  monitorType: string;
  value: number;
}

export interface ThetaConfig {
  calibrations: Array<CalibrationConfig>;
}

export interface CdxConfig {
  enable: boolean;
  user: string;
  password: string;
  siteCounty: string;
  siteID: string;
}

export interface MtRecord {
  mtName: string;
  value?: number;
  status: string;
}

export interface RecordListID {
  time: number;
  monitor: string;
}
export interface RecordList {
  mtDataList: Array<MtRecord>;
  _id: RecordListID;
}
