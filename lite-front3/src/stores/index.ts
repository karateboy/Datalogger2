import axios from 'axios'
import { defineStore } from 'pinia'

interface State {
  login: boolean
}

export const useRootStore = defineStore('rootStore', {
  // 转换为函数
  state: (): State => ({
    login: false
  }),
  getters: {},
  actions: {
    setLogin(login: boolean) {
      this.login = login
    }
  },
})
