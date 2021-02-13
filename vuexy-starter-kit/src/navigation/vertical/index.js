export default [
  {
    title: '即時資訊',
    icon: 'ActivityIcon',
    children: [
      {
        title: '儀錶板',
        route: 'home',
      },
      {
        title: '即時數據',
        route: 'realtime-data',
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
      },
      {
        title: '歷史趨勢圖',
        route: 'history-trend',
      },
      {
        title: '校正資料查詢',
        route: 'calibration-query',
      },
      {
        title: '警報記錄查詢',
        route: 'alarm-query',
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
      },
      {
        title: '月份時報表',
        route: 'monthly-hour-report',
      },
    ],
  },
  {
    title: '系統管理',
    icon: 'BookOpenIcon',
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
        title: '測項管理',
        route: 'monitor-type-config',
      },
      {
        title: '人工資料註記',
        route: 'manual-audit',
      },
      {
        title: '人工註記查詢',
        route: 'audit-log-query',
      },
      {
        title: '使用者管理',
        route: 'user-management',
      },
      {
        title: '資料管理',
        route: null,
      },
    ],
  },
];
