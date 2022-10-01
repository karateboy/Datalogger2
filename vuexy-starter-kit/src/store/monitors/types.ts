export interface Monitor {
  _id: string;
  desc: string;
  lat?: number;
  lng?: number;
  epaId?: number;
}

export interface MonitorState {
  monitors: Array<Monitor>;
  activeID: string;
}
