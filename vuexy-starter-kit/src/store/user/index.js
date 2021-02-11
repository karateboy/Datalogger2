export default {
  namespaced: true,
  state: {
    userInfo: {
      _id: "",
      name: "",
      phone: "",
      isAdmin: false
    }
  },
  getters: {
  },
  mutations: {
    setUserInfo(state, val) {
      state.userInfo._id = val._id
      state.userInfo.name = val.name
      state.userInfo.phone = val.phone
      state.userInfo.isAdmin = val.isAdmin
    }
  },
  actions: {},
}
