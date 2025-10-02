import axios from 'axios';
import { MonitorTypeGroupState, MonitorTypeGroup} from './types';
import { RootState } from '../types';
import { GetterTree } from 'vuex';
import { ActionTree } from 'vuex';
import { MutationTree } from 'vuex';
import { Module } from 'vuex';

const namespaced: boolean = true;
export const state: MonitorTypeGroupState = {
    monitorTypeGroups: Array<MonitorTypeGroup>(),
};

const getters: GetterTree<MonitorTypeGroupState, RootState> = {};

const actions: ActionTree<MonitorTypeGroupState, RootState> = {
  async fetchMonitorTypeGroups({ commit }) {
    try {
      const res = await axios.get('/MonitorTypeGroups');
      if (res.status === 200) {
        commit('setMonitorTypeGroups', res.data);
      }
    } catch (err) {
      throw new Error('error at fetchMonitors');
    }
  }
};

const mutations: MutationTree<MonitorTypeGroupState> = {
  setMonitorTypeGroups(state, val) {
    state.monitorTypeGroups = val;
  }
};

export const monitorTypeGroups: Module<MonitorTypeGroupState, RootState> = {
  namespaced,
  state,
  getters,
  actions,
  mutations,
};
