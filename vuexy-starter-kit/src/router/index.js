import Vue from 'vue';
import VueRouter from 'vue-router';

Vue.use(VueRouter);

const router = new VueRouter({
    mode: 'hash',
    base: process.env.BASE_URL,
    scrollBehavior() {
        return {x: 0, y: 0};
    },
    routes: [
        {
            path: '/',
            name: 'home',
            component: () => import('@/views/Home.vue'),
            meta: {
                pageTitle: 'Bảng điều khiển',
                breadcrumb: [
                    {
                        text: 'Bảng điều khiển',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/realtime-data',
            name: 'realtime-data',
            component: () => import('@/views/RealtimeData.vue'),
            meta: {
                pageTitle: 'Dữ liệu tức thời',
                breadcrumb: [
                    {
                        text: 'Dữ liệu tức thời',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/realtime-aqi',
            name: 'realtime-aqi',
            component: () => import('@/views/RealtimeAQI.vue'),
            meta: {
                pageTitle: 'Dữ liệu tức thời',
                breadcrumb: [
                    {
                        text: '即時AQI',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/history-data',
            name: 'history-data',
            component: () => import('@/views/HistoryData.vue'),
            meta: {
                pageTitle: '歷史資料',
                breadcrumb: [
                    {
                        text: 'Truy vấn dữ liệu',
                        active: true,
                    },
                    {
                        text: 'Tìm kiếm dữ liệu lịch sử',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/history-trend',
            name: 'history-trend',
            component: () => import('@/views/HistoryTrend.vue'),
            meta: {
                pageTitle: 'Biểu đồ dữ liệu lịch sử',
                breadcrumb: [
                    {
                        text: 'Truy vấn dữ liệu',
                        active: true,
                    },
                    {
                        text: 'Biểu đồ dữ liệu lịch sử',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/aqi-trend',
            name: 'aqi-trend',
            component: () => import('@/views/AqiTrend.vue'),
            meta: {
                pageTitle: 'AQI趨勢圖',
                breadcrumb: [
                    {
                        text: 'Truy vấn dữ liệu',
                        active: true,
                    },
                    {
                        text: 'AQI趨勢圖',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/scatter-chart',
            name: 'scatter-chart',
            component: () => import('@/views/ScatterChart.vue'),
            meta: {
                pageTitle: '雙Thông số 對比圖',
                breadcrumb: [
                    {
                        text: 'Truy vấn dữ liệu',
                        active: true,
                    },
                    {
                        text: '雙Thông số 對比圖',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/trace-query',
            name: 'trace-query',
            component: () => import('@/views/TraceQuery.vue'),
            meta: {
                pageTitle: '煙流回推軌跡線',
                breadcrumb: [
                    {
                        text: 'Truy vấn dữ liệu',
                        active: true,
                    },
                    {
                        text: '煙流回推軌跡線',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/calibration-query',
            name: 'calibration-query',
            component: () => import('@/views/CalibrationQuery.vue'),
            meta: {
                pageTitle: '校正查詢',
                breadcrumb: [
                    {
                        text: 'Truy vấn dữ liệu',
                        active: true,
                    },
                    {
                        text: '校正查詢',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/multi-calibration-query',
            name: 'multi-calibration-query',
            component: () => import('@/views/MultiCalibrationQuery.vue'),
            meta: {
                pageTitle: '多點校正查詢',
                breadcrumb: [
                    {
                        text: 'Truy vấn dữ liệu',
                        active: true,
                    },
                    {
                        text: '多點校正查詢',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/alarm-query',
            name: 'alarm-query',
            component: () => import('@/views/AlarmQuery.vue'),
            meta: {
                pageTitle: 'Truy vấn cảnh báo',
                breadcrumb: [
                    {
                        text: 'Truy vấn dữ liệu',
                        active: true,
                    },
                    {
                        text: 'Truy vấn cảnh báo',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/wind-rose-query',
            name: 'wind-rose-query',
            component: () => import('@/views/WindRose.vue'),
            meta: {
                pageTitle: '玫瑰圖查詢',
                breadcrumb: [
                    {
                        text: 'Truy vấn dữ liệu',
                        active: true,
                    },
                    {
                        text: '玫瑰圖查詢',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/report',
            name: 'report',
            component: () => import('@/views/ReportQuery.vue'),
            meta: {
                pageTitle: 'Bảng biểu kết quả quan trắc',
                breadcrumb: [
                    {
                        text: '報表查詢',
                        active: true,
                    },
                    {
                        text: 'Bảng biểu kết quả quan trắc',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/monthly-hour-report',
            name: 'monthly-hour-report',
            component: () => import('@/views/MonthlyHourReportQuery.vue'),
            meta: {
                pageTitle: '月份時報表',
                breadcrumb: [
                    {
                        text: '報表查詢',
                        active: true,
                    },
                    {
                        text: '月份時報表',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/instrument-maintenance',
            name: 'instrument-maintenance',
            component: () => import('@/views/InstrumentMaintenance.vue'),
            meta: {
                pageTitle: '維護操作',
                breadcrumb: [
                    {
                        text: '維護操作',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/instrument-management',
            name: 'instrument-management',
            component: () => import('@/views/InstrumentManagement.vue'),
            meta: {
                pageTitle: '儀器管理',
                breadcrumb: [
                    {
                        text: 'Quản lý hệ thống',
                        active: true,
                    },
                    {
                        text: '儀器管理',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/instrument-status',
            name: 'instrument-status',
            component: () => import('@/views/InstrumentStatus.vue'),
            meta: {
                pageTitle: '儀器Trạng thái查詢',
                breadcrumb: [
                    {
                        text: 'Quản lý hệ thống',
                        active: true,
                    },
                    {
                        text: '儀器Trạng thái查詢',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/monitor-config',
            name: 'monitor-config',
            component: () => import('@/views/MonitorConfig.vue'),
            meta: {
                pageTitle: 'Điểm đo管理',
                breadcrumb: [
                    {
                        text: 'Quản lý hệ thống',
                        active: true,
                    },
                    {
                        text: 'Điểm đo管理',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/calibration-config',
            name: 'calibration-config',
            component: () => import('@/views/CalibrationConfig.vue'),
            meta: {
                pageTitle: '多點校正管理',
                breadcrumb: [
                    {
                        text: 'Quản lý hệ thống',
                        active: true,
                    },
                    {
                        text: '多點校正管理',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/alarm-rule-config',
            name: 'alarm-rule-config',
            component: () => import('@/views/AlarmRuleConfig.vue'),
            meta: {
                pageTitle: '警報規則管理',
                breadcrumb: [
                    {
                        text: 'Quản lý hệ thống',
                        active: true,
                    },
                    {
                        text: '警報規則管理',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/sensor-management',
            name: 'sensor-management',
            component: () => import('@/views/SensorManagement.vue'),
            meta: {
                pageTitle: '感測器管理',
                breadcrumb: [
                    {
                        text: 'Quản lý hệ thống',
                        active: true,
                    },
                    {
                        text: '感測器管理',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/monitor-type-config',
            name: 'monitor-type-config',
            component: () => import('@/views/MonitorTypeConfig.vue'),
            meta: {
                pageTitle: 'Thông số 管理',
                breadcrumb: [
                    {
                        text: 'Quản lý hệ thống',
                        active: true,
                    },
                    {
                        text: 'Thông số 管理',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/monitor-status-config',
            name: 'monitor-status-config',
            component: () => import('@/views/MonitorStatusConfig.vue'),
            meta: {
                pageTitle: 'Trạng thái碼管理',
                breadcrumb: [
                    {
                        text: 'Quản lý hệ thống',
                        active: true,
                    },
                    {
                        text: 'Trạng thái碼管理',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/monitor-type-group',
            name: 'monitor-type-group',
            component: () => import('@/views/MonitorTypeGroup.vue'),
            meta: {
                pageTitle: 'Thông số Quản lý nhóm',
                breadcrumb: [
                    {
                        text: 'Quản lý hệ thống',
                        active: true,
                    },
                    {
                        text: 'Thông số Quản lý nhóm',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/signal-type-config',
            name: 'signal-type-config',
            component: () => import('@/views/SignalTypeConfig.vue'),
            meta: {
                pageTitle: '數位訊號管理',
                breadcrumb: [
                    {
                        text: 'Quản lý hệ thống',
                        active: true,
                    },
                    {
                        text: '數位訊號管理',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/manual-audit',
            name: 'manual-audit',
            component: () => import('@/views/ManualAudit.vue'),
            meta: {
                pageTitle: '人工註記',
                breadcrumb: [
                    {
                        text: 'Quản lý hệ thống',
                        active: true,
                    },
                    {
                        text: '人工註記',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/audit-log-query',
            name: 'audit-log-query',
            component: () => import('@/views/AuditLogQuery.vue'),
            meta: {
                pageTitle: '人工註記查詢',
                breadcrumb: [
                    {
                        text: 'Quản lý hệ thống',
                        active: true,
                    },
                    {
                        text: '人工註記查詢',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/user-management',
            name: 'user-management',
            component: () => import('@/views/UserManagement.vue'),
            meta: {
                pageTitle: 'Quản lý người dùng',
                breadcrumb: [
                    {
                        text: 'Quản lý hệ thống',
                        active: true,
                    },
                    {
                        text: 'Quản lý người dùng',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/group-management',
            name: 'group-management',
            component: () => import('@/views/GroupManagement.vue'),
            meta: {
                pageTitle: 'Quản lý nhóm',
                breadcrumb: [
                    {
                        text: 'Quản lý hệ thống',
                        active: true,
                    },
                    {
                        text: 'Quản lý nhóm',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/data-management',
            name: 'data-management',
            component: () => import('@/views/DataManagement.vue'),
            meta: {
                pageTitle: 'Tải dữ liệu lên',
                breadcrumb: [
                    {
                        text: 'Quản lý hệ thống',
                        active: true,
                    },
                    {
                        text: 'Tải dữ liệu lên',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/cdx-upload-config',
            name: 'cdx-upload-config',
            component: () => import('@/views/CdxUploadConfig.vue'),
            meta: {
                pageTitle: 'CDX上傳設定',
                breadcrumb: [
                    {
                        text: 'Quản lý hệ thống',
                        active: true,
                    },
                    {
                        text: 'CDX上傳設定',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/system-config',
            name: 'system-config',
            component: () => import('@/views/SystemConfig.vue'),
            meta: {
                pageTitle: 'Cài đặt tham số',
                breadcrumb: [
                    {
                        text: 'Quản lý hệ thống',
                        active: true,
                    },
                    {
                        text: 'Cài đặt tham số',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/audit-config',
            name: 'audit-config',
            component: () => import('@/views/AuditConfig.vue'),
            meta: {
                pageTitle: '資料檢核設定',
                breadcrumb: [
                    {
                        text: 'Quản lý hệ thống',
                        active: true,
                    },
                    {
                        text: '資料檢核設定',
                        active: true,
                    },
                ],
            },
        },
        {
            path: '/login',
            name: 'login',
            component: () => import('@/views/Login.vue'),
            meta: {
                layout: 'full',
            },
        },
        {
            path: '/error-404',
            name: 'error-404',
            component: () => import('@/views/error/Error404.vue'),
            meta: {
                layout: 'full',
            },
        },
        {
            path: '*',
            redirect: 'error-404',
        },
    ],
});

export default router;
