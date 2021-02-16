import Vue from 'vue';
import Vuex from 'vuex';

// Modules
import app from './app';
import appConfig from './app-config';
import verticalMenu from './vertical-menu';
import monitorTypes from './monitorTypes';
import monitors from './monitors';
import user from './user';

Vue.use(Vuex);

export default new Vuex.Store({
  modules: {
    app,
    appConfig,
    verticalMenu,
    monitorTypes,
    monitors,
    user,
  },
  strict: process.env.DEV,
});
