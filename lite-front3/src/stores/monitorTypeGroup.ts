import axios from 'axios'
import { defineStore } from 'pinia'

interface MonitorTypeGroup {
  _id: string
  name: string
  mts: Array<string>
}

interface State {
  monitorTypeGroups: Array<MonitorTypeGroup>
}

export const useMonitorTypeGroupStore = defineStore('monitorTypeGroupStore', {
  // 转换为函数
  state: (): State => ({
    monitorTypeGroups:[]
  }),
  getters: {
  },
  actions: {
    async fetchMonitorTypeGroups() {
      try {
        const res = await axios.get('/MonitorTypeGroups')
        if (res.status === 200) {
          this.monitorTypeGroups = res.data
        }
      } catch (err) {
        throw new Error('error at fetchMonitorTypeGroups')
      }
    },
  },
})

