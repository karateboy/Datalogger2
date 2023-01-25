export default [
  {
    title: '即時資訊',
    icon: 'ActivityIcon',
    children: [
      {
        title: '儀錶板',
        route: 'home',
        action: 'read',
        resource: 'Dashboard',
      },
    ],
  },
  {
    title: '數據查詢',
    icon: 'DatabaseIcon',
    children: [
      {
        title: '歷史資料查詢',
        route: 'history-data',
        action: 'read',
        resource: 'Data',
      },
      {
        title: '歷史趨勢圖',
        route: 'history-trend',
        action: 'read',
        resource: 'Data',
      },
    ],
  },
  {
    title: '報表查詢',
    icon: 'BookOpenIcon',
    children: [
      {
        title: '監測報表',
        route: 'report',
        action: 'read',
        resource: 'Data',
      },
      {
        title: '月份時報表',
        route: 'monthly-hour-report',
        action: 'read',
        resource: 'Data',
      },
    ],
  },
  {
    title: '系統管理',
    icon: 'SettingsIcon',
    children: [
      {
        title: '儀器管理',
        route: 'instrument-management',
      },
      {
        title: '儀器狀態查詢',
        route: 'instrument-status',
      },
      {
        title: '測點管理',
        route: 'monitor-config',
      },
      {
        title: '測項管理',
        route: 'monitor-type-config',
      },
      {
        title: '數位訊號管理',
        route: 'signal-type-config',
      },
      {
        title: '用戶用電輸入',
        route: 'power-usage-edit',
      },
      {
        title: '使用者管理',
        route: 'user-management',
      },
      {
        title: '群組管理',
        route: 'group-management',
      },
      {
        title: '資料檢核設定',
        route: 'audit-config',
      },
      {
        title: '參數設定',
        route: 'system-config',
      },
    ],
  },
];
