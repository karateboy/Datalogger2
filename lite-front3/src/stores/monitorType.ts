import axios from 'axios'
import { defineStore } from 'pinia'
import {MonitorType} from "@/types";

interface State {
  monitorTypes: Array<MonitorType>
}

export const useMonitorTypeStore = defineStore('monitorTypeStore', {
  // 转换为函数
  state: (): State => ({
   monitorTypes: [],
  }),
  getters: {
    mtMap(state) {
      const map = new Map()
      for (const mt of state.monitorTypes) {
        map.set(mt._id, mt)
      }
      return map
    },
    activatedMonitorTypes(state) {
      return state.monitorTypes.filter(mt => mt.measuringBy)
    },
  },
  actions: {
    async fetchMonitorTypes() {
      try {
        const res = await axios.get('/MonitorType')
        if(res.status === 200) {
          this.monitorTypes = res.data
        }
      } catch (err) {
        console.error(err)
      }
    },
  },
})
