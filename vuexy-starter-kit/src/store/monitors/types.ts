export interface Monitor {
  _id: string;
  desc: string;
}

export interface MonitorState {
  monitors: Array<Monitor>;
  activeID: string;
}
