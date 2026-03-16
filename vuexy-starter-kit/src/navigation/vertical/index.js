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
                action: 'read',
                resource: 'Dashboard',
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
                action: 'read',
                resource: 'Data',
            },
        ],
    },
    {
        title: 'Quản lý hệ thống',
        icon: 'SettingsIcon',
        children: [
            {
                title: '儀器管理',
                route: 'instrument-management',
            },
            {
                title: 'Điểm đo管理',
                route: 'monitor-config',
            },
            {
                title: 'Thông số 管理',
                route: 'monitor-type-config',
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
                title: 'Tải dữ liệu lên',
                route: 'data-management',
            },
            {
                title: 'Cài đặt tham số',
                route: 'system-config',
            },
        ],
    },
];
