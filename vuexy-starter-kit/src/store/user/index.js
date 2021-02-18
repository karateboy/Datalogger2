export default {
  namespaced: true,
  state: {
    login: false,
    userInfo: {
      _id: '',
      name: '',
      phone: '',
      isAdmin: false,
    },
  },
  getters: {},
  mutations: {
    setUserInfo(state, val) {
      state.userInfo._id = val._id;
      state.userInfo.name = val.name;
      state.userInfo.phone = val.phone;
      state.userInfo.isAdmin = val.isAdmin;
    },
    setLogin(state, val) {
      state.login = val;
    },
  },
  actions: {},
};
