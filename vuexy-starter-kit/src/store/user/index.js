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
      alertEmail: undefined,
      smsPhone: undefined,
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
      state.userInfo.alertEmail = val.alertEmail;
      state.userInfo.smsPhone = val.smsPhone;
    },
    setLogin(state, val) {
      state.login = val;
    },
  },
  actions: {},
};
