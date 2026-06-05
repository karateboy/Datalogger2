import { createApp } from 'vue'
import { createPinia } from 'pinia'



import App from '@/App.vue'
import { registerPlugins } from '@core/utils/plugins'
import axios from 'axios'

// Styles
import '@core/scss/template/index.scss'
import '@styles/styles.scss'

axios.defaults.baseURL =
  process.env.NODE_ENV === 'development' ? 'http://localhost:9000/' : ''
axios.defaults.withCredentials = true

// Create vue app
const app = createApp(App)

// Register plugins
registerPlugins(app)
// Mount vue app
app.mount('#app')
