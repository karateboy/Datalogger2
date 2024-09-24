import axios from 'axios';
import { TableState } from './types';
import { RootState } from '../types';
import { ActionTree, GetterTree, Module, MutationTree } from 'vuex';

const namespaced: boolean = true;
export const state: TableState = {
  tables: [],
};

const getters: GetterTree<TableState, RootState> = {
  dataTypes() {
    const dataTypes = [];
    for (const table of state.tables) {
      if (table.indexOf('hour_data') !== -1) {
        if (table === 'hour_data')
          dataTypes.push({ id: 'hour', txt: '小時資料' });
        else {
          const txt = table.replace('hour_data_', '小時資料(') + ')';
          dataTypes.push({ id: table, txt });
        }
      } else if (table.indexOf('min_data') !== -1) {
        if (table === 'min_data')
          dataTypes.push({ id: 'min', txt: '分鐘資料' });
        else {
          const txt = table.replace('min_data_', '分鐘資料(') + ')';
          dataTypes.push({ id: table, txt });
        }
      }
    }
    return dataTypes;
  },
  hourDataTypes() {
    const dataTypes = [];
    for (const table of state.tables) {
      if (table.indexOf('hour_data') !== -1) {
        if (table === 'hour_data')
          dataTypes.push({ id: 'hour', txt: '小時資料' });
        else {
          const txt = table.replace('hour_data_', '小時資料(') + ')';
          dataTypes.push({ id: table, txt });
        }
      }
    }
    return dataTypes;
  },
};

const actions: ActionTree<TableState, RootState> = {
  async fetchTables({ commit }) {
    try {
      const res = await axios.get('/Tables');
      if (res.status === 200) {
        commit('setTables', res.data);
      }
    } catch (err) {
      throw new Error('error at fetchTables');
    }
  },
};

const mutations: MutationTree<TableState> = {
  setTables(state, val) {
    state.tables = val;
  },
};

export const tables: Module<TableState, RootState> = {
  namespaced,
  state,
  getters,
  actions,
  mutations,
};
