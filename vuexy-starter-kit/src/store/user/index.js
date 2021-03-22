export default {
  namespaced: true,
  state: {
    login: false,
    userInfo: {
      _id: '',
      name: '',
      phone: '',
      isAdmin: false,
      group: '',
      monitorTypeOfInterest: [],
    },
  },
  getters: {},
  mutations: {
    setUserInfo(state, val) {
      state.userInfo._id = val._id;
      state.userInfo.name = val.name;
      state.userInfo.isAdmin = val.isAdmin;
      state.userInfo.group = val.group;
      state.userInfo.monitorTypeOfInterest = val.monitorTypeOfInterest;
    },
    setLogin(state, val) {
      state.login = val;
    },
  },
  actions: {},
};
