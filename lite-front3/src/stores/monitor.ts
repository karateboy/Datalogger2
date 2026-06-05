import axios from 'axios'
import { defineStore } from 'pinia'
import {Monitor} from "@/types";

interface State {
  monitors: Array<Monitor>
  activeID: string
}

export const useMonitorStore = defineStore('monitorStore', {
  // 转换为函数
  state: (): State => ({
    monitors: Array<Monitor>(),
    activeID: 'me',
  }),
  getters: {
    // 不在需要 firstName getter，移除
    mMap: (state) => {
      const map = new Map<string, Monitor>()
      for (const m of state.monitors) {
        map.set(m._id, m)
      }
      return map
    },
  },
  actions: {
    async fetchMonitors() {
      try {
        const res = await axios.get('/Monitors')
        if (res.status === 200) {
          this.monitors = res.data
        }
      } catch (err) {
        throw new Error('error at fetchMonitors')
      }
    },
    async getActiveID() {
      try {
        const res = await axios.get('/ActiveMonitor')
        if (res.status === 200) {
          this.activeID = res.data
        }
      } catch (err) {
        throw new Error('error at getActiveID')
      }
    },
    async setActiveID(id: string) {
      try {
        const res = await axios.put(`/ActiveMonitor/${id}`, {})
        if (res.status == 200) {
          this.activeID = id;
        }
      } catch (err) {
        throw new Error('error at setActiveID')
      }
    },
  },
})
