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
      {
        title: '即時數據',
        route: 'realtime-data',
      },
    ],
  },
  {
    title: 'Truy vấn dữ liệu',
    icon: 'DatabaseIcon',
    children: [
      {
        title: 'Tìm kiếm dữ liệu lịch sử',
        route: 'history-data',
        action: 'read',
        resource: 'Data',
      },
      {
        title: 'Biểu đồ dữ liệu lịch sử',
        route: 'history-trend',
        action: 'read',
        resource: 'Data',
      },
      {
        title: '校正資料查詢',
        route: 'calibration-query',
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
        title: 'Bảng biểu kết quả quan trắc',
        route: 'report',
      },
      {
        title: '月份時報表',
        route: 'monthly-hour-report',
      },
    ],
  },
  {
    title: 'Quản lý hệ thống',
    icon: 'BookOpenIcon',
    children: [
      {
        title: '儀器管理',
        route: 'instrument-management',
      },
      {
        title: '儀器Trạng thái查詢',
        route: 'instrument-status',
      },
      {
        title: 'Điểm đo管理',
        route: 'monitor-config',
        action: 'set',
        resource: 'Alarm',
      },
      {
        title: 'Thông số 管理',
        route: 'monitor-type-config',
        action: 'set',
        resource: 'Alarm',
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
        title: 'Quản lý người dùng',
        route: 'user-management',
      },
      {
        title: 'Quản lý nhóm',
        route: 'group-management',
      },
      {
        title: '資料管理',
        route: 'data-management',
      },
    ],
  },
];
