<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="6">
            <b-form-group
                label="報表種類"
                label-for="reportType"
                label-cols-md="3"
            >
              <v-select
                  id="reportType"
                  v-model="form.reportType"
                  label="txt"
                  :reduce="dt => dt.id"
                  :options="reportTypes"
              />
            </b-form-group>
          </b-col>
          <b-col cols="6">
            <b-form-group
                label="查詢日期"
                label-for="dataRange"
                label-cols-md="3"
            >
              <date-picker
                  id="dataRange"
                  v-model="form.date"
                  :type="pickerType"
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
        <b-table
            responsive
            striped
            hover
            :fields="columns"
            :items="rows"
            bordered
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
      </div>
    </b-card>
  </div>
</template>
<script lang="ts">
import Vue from 'vue';
import DatePicker from 'vue2-datepicker';
import 'vue2-datepicker/index.css';
import 'vue2-datepicker/locale/zh-tw';
import {DisplayReport, RowData} from './types';
import moment from 'moment';
import axios from 'axios';

const Ripple = require('vue-ripple-directive');

const excel = require('../libs/excel');
const _ = require('lodash');

interface RowDataReport extends RowData {
  time?: string;
}

export default Vue.extend({
  components: {
    DatePicker,
  },
  directives: {
    Ripple,
  },
  data() {
    const date = moment().valueOf();
    return {
      display: false,
      reportTypes: [
        {id: 'daily', txt: '日報'},
        {id: 'monthly', txt: '月報'},
        {id: 'yearly', txt: '年報'},
      ],
      columns: Array<any>(),
      statRows: Array<any>(),
      rows: Array<RowDataReport>(),
      form: {
        date,
        reportType: 'daily',
      },
    };
  },
  computed: {
    pickerType() {
      if (this.form.reportType === 'daily') return 'date';
      if (this.form.reportType === 'monthly') return 'month';
      return 'year';
    },
  },
  methods: {
    async query() {
      this.display = true;
      const url = `/monitorReport/${this.form.reportType}/${this.form.date}`;
      const res = await axios.get(url);
      this.handleReport(res.data);
    },
    downloadReport() {
      const baseUrl =
          process.env.NODE_ENV === 'development' ? 'http://localhost:9000/' : '/';

      const url = `${baseUrl}Excel/monitorReport/${this.form.reportType}/${this.form.date}`;

      window.open(url);
    },
    handleReport(report: DisplayReport) {
      this.columns.splice(0, this.columns.length);
      switch (this.form.reportType) {
        case 'daily':
          this.columns.push({
            key: 'time',
            label: '時間',
            sortable: true,
          });
          break;
        case 'monthly':
          this.columns.push({
            key: 'time',
            label: '日期',
            sortable: true,
          });
          break;
        case 'yearly':
          this.columns.push({
            key: 'time',
            label: '月份',
            sortable: true,
          });
          break;
      }

      for (let i = 0; i < report.columnNames.length; i++) {
        this.columns.push({
          key: `cellData[${i}].v`,
          label: `${report.columnNames[i]}`,
          tdClass: this.cellDataTd(i),
          sortable: true,
        });
      }
      for (const r of report.rows) {
        const row = r as RowDataReport;
        switch (this.form.reportType) {
          case 'daily':
            row.time = moment(row.date).format('HH:mm');
            break;
          case 'monthly':
            row.time = moment(row.date).format('MM/DD');
            break;
          case 'yearly':
            row.time = moment(row.date).format('MM');
            break;
        }
      }
      this.rows = report.rows;
      this.statRows = report.statRows;
    },
    cellDataTd(i: number) {
      return (_value: any, _key: any, item: any) =>
          item.cellData[i].cellClassName;
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
      let filename =
          this.form.reportType === 'daily'
              ? `${moment(this.form.date).format('ll')}日報`
              : `${moment(this.form.date).year()}年${
                  moment(this.form.date).month() + 1
              }月報`;

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
