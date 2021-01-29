import axios from "axios"

export default {
  namespaced: true,
  state: {
    monitorTypes: []
  },
  getters: {},
  mutations: {
    updateMonitorTypes(state, val) {
      state.monitorTypes = val
    }
  },
  actions: {
    fetchMonitorTypes({ commit }) {
      axios.get("/MonitorType").then(res => {
        const payload = res && res.data
        commit('updateMonitorTypes', payload)
      }).catch(err => { throw new Error(err) })
    }
  },
}
