<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="12">
            <b-form-group label="測點" label-for="monitor" label-cols-md="3">
              <v-select
                id="monitor"
                v-model="form.monitor"
                label="desc"
                :reduce="m => m._id"
                :options="monitors"
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group label="儀器" label-for="instrument" label-cols-md="3">
              <v-select
                id="instrument"
                v-model="form.instrument"
                :reduce="inst => inst"
                :options="instruments"
              />
            </b-form-group>
          </b-col>
        </b-row>
        <b-row>
          <b-col cols="12">
            <b-form-group
              label="資料區間"
              label-for="dataRange"
              label-cols-md="3"
            >
              <date-picker
                id="dataRange"
                v-model="form.range"
                :range="true"
                type="date"
                format="YYYY-MM-DD"
                value-type="timestamp"
                :show-second="false"
              />
            </b-form-group>
          </b-col>
        </b-row>
        <b-row>
          <!-- submit and reset -->
          <b-col offset-md="3">
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              type="submit"
              variant="primary"
              class="mr-1"
              :disabled="!canQuery"
              @click="query"
            >
              查詢
            </b-button>
            <b-button
              v-ripple.400="'rgba(186, 191, 199, 0.15)'"
              type="reset"
              class="mr-1"
              variant="outline-secondary"
            >
              取消
            </b-button>
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              type="submit"
              variant="primary"
              class="mr-1"
              @click="exportExcel"
            >
              下載Excel
            </b-button>
          </b-col>
        </b-row>
      </b-form>
    </b-card>
    <b-card v-show="display">
      <div>
        <b-table
          responsive
          :fields="columns"
          :items="rows"
          show-empty
          :per-page="15"
          :current-page="currentPage"
          bordered
          sticky-header="800px"
          empty-text="沒有資料"
          small
        >
          <template #custom-foot>
            <b-tr v-for="stat in statRows" :key="stat.name">
              <b-th>{{ stat.name }}</b-th>
              <th v-for="(cell, i) in stat.cellData" :key="i">
                {{ cell.v }}
              </th>
            </b-tr>
          </template>
        </b-table>
        <b-pagination
          v-model="currentPage"
          :total-rows="rows.length"
          :per-page="15"
          first-text="⏮"
          prev-text="⏪"
          next-text="⏩"
          last-text="⏭"
          class="mt-4"
        ></b-pagination>
      </div>
    </b-card>
  </div>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script lang="ts">
import Vue from 'vue';
import DatePicker from 'vue2-datepicker';
import vSelect from 'vue-select';
import 'vue2-datepicker/index.css';
import 'vue2-datepicker/locale/zh-tw';
const Ripple = require('vue-ripple-directive');
import moment from 'moment';
import axios from 'axios';
const excel = require('../libs/excel');
const _ = require('lodash');
import { mapState, mapActions, mapGetters, mapMutations } from 'vuex';

export default Vue.extend({
  components: {
    DatePicker,
    vSelect,
  },
  directives: {
    Ripple,
  },
  data() {
    const range = [moment().subtract(1, 'days').valueOf(), moment().valueOf()];
    return {
      display: false,
      columns: Array<any>(),
      statRows: [],
      rows: [],
      instruments: Array<any>(),
      form: {
        monitor: '',
        instrument: '',
        range,
      },
      currentPage: 1,
    };
  },
  computed: {
    ...mapState('monitors', ['monitors']),
    ...mapGetters('monitors', ['mMap']),
    ...mapGetters('monitorTypes', ['mtMap']),
    canQuery(): boolean {
      if (this.form.monitor && this.form.instrument) return true;

      return false;
    },
  },
  watch: {
    'form.monitor': async function (newOne: string, oldOne: string) {
      await this.getMonitorInstruments();
    },
  },
  async mounted() {
    await this.fetchMonitors();
    await this.fetchMonitorTypes();
    if (this.monitors.length !== 0) this.form.monitor = this.monitors[0]._id;
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors']),
    ...mapMutations(['setLoading']),
    async getMonitorInstruments() {
      try {
        let res = await axios.get(`/MonitorInstrument/${this.form.monitor}`);
        if (res.status === 200) {
          const ret = res.data;
          this.instruments = ret;
          if (this.instruments.length !== 0) {
            this.form.instrument = this.instruments[0];
          } else this.form.instrument = '';
        }
      } catch (err) {
        console.error(`$err`);
      }
    },
    async query() {
      this.display = true;
      try {
        this.setLoading({ loading: true });
        const url = `/MonitorInstrumentStatusReport/${this.form.monitor}/${this.form.instrument}/${this.form.range[0]}/${this.form.range[1]}`;
        const res = await axios.get(url);
        this.handleReport(res.data);
      } catch (err) {
        console.error(`${err}`);
      } finally {
        this.setLoading({ loading: false });
      }
    },
    handleReport(report: any) {
      this.columns.splice(0, this.columns.length);

      this.columns.push({
        key: 'time',
        label: '時間',
        sortable: true,
      });

      for (let i = 0; i < report.columnNames.length; i++) {
        this.columns.push({
          key: `cellData[${i}].v`,
          label: `${report.columnNames[i]}`,
          sortable: true,
          stickyColumn: true,
        });
      }
      for (const row of report.rows) {
        row.time = moment(row.date).format('lll');
      }
      this.rows = report.rows;
      this.statRows = report.statRows;
    },
    exportExcel() {
      const title = this.columns.map(e => e.label);
      const key = this.columns.map(e => e.key);
      for (let entry of this.rows) {
        let e = entry as any;
        for (let k of key) {
          e[k] = _.get(entry, k);
        }
      }
      const params = {
        title,
        key,
        data: this.rows,
        autoWidth: true,
        filename: `${this.form.instrument}儀器參數`,
      };
      excel.export_array_to_excel(params);
    },
  },
});
</script>
