export default [
  {
    title: 'Realtime',
    icon: 'ActivityIcon',
    children: [
      {
        title: 'Dashboard',
        route: 'home',
        action: 'read',
        resource: 'Dashboard',
      },
    ],
  },
  {
    title: 'DataQuery',
    icon: 'DatabaseIcon',
    children: [
      {
        title: 'HistoryData',
        route: 'history-data',
        action: 'read',
        resource: 'Data',
      },
      {
        title: 'TrendChart',
        route: 'history-trend',
        action: 'read',
        resource: 'Data',
      },
      {
        title: 'AlarmQuery',
        route: 'alarm-query',
        action: 'read',
        resource: 'Data',
      },
    ],
  },
  {
    title: 'Report',
    icon: 'BookOpenIcon',
    route: 'report',
    action: 'read',
    resource: 'Data',
  },
  {
    title: 'System',
    icon: 'SettingsIcon',
    children: [
      {
        title: 'Instrument',
        route: 'instrument-management',
      },
      {
        title: 'MonitorTypeConfig',
        route: 'monitor-type-config',
      },
      {
        title: 'DI/DO',
        route: 'signal-type-config',
      },
      {
        title: 'UserManagement',
        route: 'user-management',
      },
      {
        title: 'GroupManagement',
        route: 'group-management',
      },
    ],
  },
]
