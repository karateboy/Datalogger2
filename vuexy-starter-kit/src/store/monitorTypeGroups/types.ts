export interface MonitorTypeGroup {
  _id: string;
  name: string;
  mts: Array<string>;
}

export interface MonitorTypeGroupState {
  monitorTypeGroups: Array<MonitorTypeGroup>;
}
