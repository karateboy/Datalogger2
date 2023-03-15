export interface Monitor {
  _id: string;
  desc: string;
  monitorTypes: Array<string>;
  location?: Array<number>;
}

export interface MonitorState {
  monitors: Array<Monitor>;
}
