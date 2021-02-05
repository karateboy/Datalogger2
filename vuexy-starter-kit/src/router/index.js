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
