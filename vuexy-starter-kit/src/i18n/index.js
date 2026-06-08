import Vue from 'vue'
import VueI18n from 'vue-i18n'

Vue.use(VueI18n)
import zh from './locales/zh.json'
import en from './locales/en.json'

export const i18n = new VueI18n({
  locale: localStorage.getItem('locale') || 'en',
  messages: {
    zh,
    en,
  },
})
export default i18n
