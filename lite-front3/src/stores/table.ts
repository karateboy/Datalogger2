import axios from 'axios'
import { defineStore } from 'pinia'

interface State {
  tables: Array<string>
}
export const useTableStore = defineStore('tableStore', {
  // 转换为函数
  state: (): State => ({
    tables:[]
  }),
  getters: {
    dataTypes(state) {
      const dataTypes = []
      for (const table of state.tables) {
        if (table.indexOf('hour_data') !== -1) {
          if (table === 'hour_data')
            dataTypes.push({ id: 'hour', txt: '小時資料' })
          else {
            const txt = table.replace('hour_data_', '小時資料(') + ')'
            dataTypes.push({ id: table, txt })
          }
        } else if (table.indexOf('min_data') !== -1) {
          if (table === 'min_data') dataTypes.push({ id: 'min', txt: '分鐘資料' })
          else {
            const txt = table.replace('min_data_', '分鐘資料(') + ')'
            dataTypes.push({ id: table, txt })
          }
        }
      }
      return dataTypes
    },
    hourDataTypes(state) {
      const dataTypes = []
      for (const table of state.tables) {
        if (table.indexOf('hour_data') !== -1) {
          if (table === 'hour_data')
            dataTypes.push({ id: 'hour', txt: '小時資料' })
          else {
            const txt = table.replace('hour_data_', '小時資料(') + ')'
            dataTypes.push({ id: table, txt })
          }
        }
      }
      return dataTypes
    }
  },
  actions: {
    async fetchTables() {
      try {
        const res = await axios.get('/Tables')
        if (res.status === 200) {
          this.tables = res.data
        }
      } catch (err) {
        throw new Error('error at fetchTables')
      }
    },
  },
})
