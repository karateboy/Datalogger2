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
  value: string;
}
