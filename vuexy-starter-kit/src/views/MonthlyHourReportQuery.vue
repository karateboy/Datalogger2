<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="12">
            <b-form-group
              label="測項"
              label-for="monitorType"
              label-cols-md="3"
            >
              <v-select
                id="monitorType"
                v-model="form.monitorType"
                label="desp"
                :reduce="mt => mt._id"
                :options="activatedMonitorTypes"
              />
            </b-form-group>
          </b-col>
        </b-row>
        <b-row>
          <b-col cols="12">
            <b-form-group
              label="查詢月份"
              label-for="dataRange"
              label-cols-md="3"
            >
              <date-picker
                id="dataRange"
                v-model="form.date"
                type="month"
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
              @click="downloadReport"
            >
              下載Excel
            </b-button>
          </b-col>
        </b-row>
      </b-form>
    </b-card>
    <b-card v-show="display">
      <div>
        <b-table responsive hover :fields="columns" :items="rows" bordered>
          <template #custom-foot>
            <b-tr v-for="stat in statRows" :key="stat.name">
              <b-th>{{ stat.name }}</b-th>
              <th v-for="(cell, i) in stat.cellData" :key="i">
                {{ cell.v }}
              </th>
            </b-tr>
          </template>
        </b-table>
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
import { mapState, mapActions, mapGetters } from 'vuex';
import moment from 'moment';
import axios from 'axios';
const excel = require('../libs/excel');
const _ = require('lodash');

export default Vue.extend({
  components: {
    DatePicker,
    vSelect,
  },
  directives: {
    Ripple,
  },
  data() {
    const date = moment().valueOf();
    return {
      display: false,
      columns: Array<any>(),
      statRows: [],
      rows: [],
      form: {
        date,
        monitorType: undefined,
      },
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapGetters('monitorTypes', ['activatedMonitorTypes']),
  },
  mounted() {
    this.fetchMonitorTypes().then(() => {
      if (this.monitorTypes.length !== 0) {
        this.form.monitorType = this.monitorTypes[0]._id;
      }
    });
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    async query() {
      this.display = true;
      const url = `/MonthlyHourReport/${this.form.monitorType}/${this.form.date}`;
      const res = await axios.get(url);
      this.handleReport(res.data);
    },
    downloadReport() {
      const baseUrl =
        process.env.NODE_ENV === 'development' ? 'http://localhost:9000/' : '/';

      const url = `${baseUrl}Excel/MonthlyHourReport/${this.form.monitorType}/${this.form.date}`;

      window.open(url);
    },
    cellDataTd(i: number) {
      return (_value: any, _key: any, item: any) =>
        item.cellData[i].cellClassName;
    },
    handleReport(report: any) {
      this.columns.splice(0, this.columns.length);

      this.columns.push({
        key: 'time',
        label: '日\\時間',
        sortable: true,
      });

      for (let i = 0; i < report.columnNames.length; i++) {
        this.columns.push({
          key: `cellData[${i}].v`,
          label: `${report.columnNames[i]}`,
          sortable: true,
          tdClass: this.cellDataTd(i),
          stickyColumn: true,
        });
      }
      for (const row of report.rows) {
        row.time = moment(row.date).format('MM/DD');
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
      let filename = `${moment(this.form.date).year()}年${
        moment(this.form.date).month() + 1
      }月分時報表`;

      const params = {
        title,
        key,
        data: this.rows,
        autoWidth: true,
        filename,
      };
      excel.export_array_to_excel(params);
    },
  },
});
</script>

<style></style>
