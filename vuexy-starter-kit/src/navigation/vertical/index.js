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
      {
        title: '雙測項對比圖',
        route: 'scatter-chart',
        action: 'read',
        resource: 'Data',
      },
      //地表變形趨勢圖
      {
        title: '地表變形趨勢圖',
        route: 'gps-trend',
        action: 'read',
        resource: 'Data',
      },
      {
        title: '土壤氣體圖',
        route: 'ground-gas',
        action: 'read',
        resource: 'Data',
      },
      {
        title: '地下水質圖',
        route: 'underground-water',
        action: 'read',
        resource: 'Data',
      },
      {
        title: '玫瑰圖查詢',
        route: 'wind-rose-query',
      },
      {
        title: '年度地震查詢',
        route: 'earthquake-event-query',
        action: 'read',
        resource: 'Data',
      },
      {
        title: '每日波形查詢',
        route: 'wave-query',
        action: 'read',
        resource: 'Data',
      },
      {
        title: '警報記錄查詢',
        route: 'alarm-query',
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
        title: '測點管理',
        route: 'monitor-config',
      },
      {
        title: '測項管理',
        route: 'monitor-type-config',
      },
      {
        title: '人工資料註記',
        route: 'manual-audit',
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
        title: '資料管理',
        route: 'data-management',
      },
      {
        title: '參數設定',
        route: 'system-config',
      },
    ],
  },
];
