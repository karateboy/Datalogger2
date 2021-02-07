import Vue from 'vue'
import VueRouter from 'vue-router'

Vue.use(VueRouter)

const router = new VueRouter({
  mode: 'history',
  base: process.env.BASE_URL,
  scrollBehavior() {
    return { x: 0, y: 0 }
  },
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/Home.vue'),
      meta: {
        pageTitle: '儀表板',
        breadcrumb: [
          {
            text: '儀表板',
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
        pageTitle: '即時資料',
        breadcrumb: [
          {
            text: '即時資料',
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
            text: '數據查詢',
            active: true,
          },
          {
            text: '歷史資料查詢',
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
        pageTitle: '歷史趨勢圖',
        breadcrumb: [
          {
            text: '數據查詢',
            active: true,
          },
          {
            text: '歷史趨勢圖',
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
            text: '數據查詢',
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
      path: '/alarm-query',
      name: 'alarm-query',
      component: () => import('@/views/AlarmQuery.vue'),
      meta: {
        pageTitle: '警報查詢',
        breadcrumb: [
          {
            text: '數據查詢',
            active: true,
          },
          {
            text: '警報查詢',
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
        pageTitle: '監測報表',
        breadcrumb: [
          {
            text: '報表查詢',
            active: true,
          },
          {
            text: '監測報表',
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
})

export default router
