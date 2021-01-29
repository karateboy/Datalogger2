import Vue from 'vue'
import { BootstrapVue, IconsPlugin, ToastPlugin, ModalPlugin } from 'bootstrap-vue'
import VueCompositionAPI from '@vue/composition-api'
import axios from 'axios'
import jscookie from "js-cookie"
import router from './router'
import store from './store'
import App from './App.vue'

// Global Components
import './global-components'

// 3rd party plugins
import '@/libs/portal-vue'
import '@/libs/toastification'

axios.defaults.baseURL = process.env.NODE_ENV === 'development' ? 'http://localhost:9000/' : ''
axios.defaults.withCredentials = true

// BSV Plugin Registration
Vue.use(BootstrapVue)
Vue.use(IconsPlugin)
Vue.use(ToastPlugin)
Vue.use(ModalPlugin)

// Composition API
Vue.use(VueCompositionAPI)

// import core styles
require('@core/scss/core.scss')

// import assets styles
require('@/assets/scss/style.scss')

Vue.config.productionTip = false

router.beforeEach((to, from, next) => {
  const isAuthenticated = jscookie.get("authenticated")
  if (isAuthenticated || to.name === 'login') {
    next()
  } else {
    next({ name: 'login' })
  }
})

new Vue({
  router,
  store,
  render: h => h(App),
}).$mount('#app')
