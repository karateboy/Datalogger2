<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
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
                type="datetime"
                format="YYYY-MM-DD HH:mm"
                value-type="timestamp"
                :show-second="false"
              />
            </b-form-group>
          </b-col>
          <!-- submit and reset -->
          <b-col>
            <b-button
              type="submit"
              variant="gradient-primary"
              class="mr-1"
              @click="query"
            >
              查詢
            </b-button>
            <b-button
              type="submit"
              variant="gradient-primary"
              class="mr-1"
              @click="downloadExcel"
            >
              匯出Excel
            </b-button>
            <b-button
              v-ripple.400="'rgba(186, 191, 199, 0.15)'"
              type="reset"
              variant="outline-secondary"
            >
              取消
            </b-button>
          </b-col>
        </b-row>
      </b-form>
    </b-card>
    <b-card v-show="display">
      <b-table striped hover :fields="columns" :items="rows" />
    </b-card>
  </div>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script lang="ts">
import Vue from 'vue';
import DatePicker from 'vue2-datepicker';
import 'vue2-datepicker/index.css';
import 'vue2-datepicker/locale/zh-tw';
const Ripple = require('vue-ripple-directive');
import moment from 'moment';
import axios from 'axios';
import { mapActions, mapGetters } from 'vuex';
import { MonitorType } from './types';
const excel = require('../libs/excel');
const _ = require('lodash');

interface CalibrationJSON {
  monitorType: string;
  startTime: number;
  endTime: number;
  // eslint-disable-next-line camelcase
  zero_val?: number;
  // eslint-disable-next-line camelcase
  span_std?: number;
  // eslint-disable-next-line camelcase
  span_val?: number;
}

export default Vue.extend({
  components: {
    DatePicker,
  },
  directives: {
    Ripple,
  },

  data() {
    const range = [moment().subtract(1, 'days').valueOf(), moment().valueOf()];
    let rows = Array<CalibrationJSON>();
    return {
      display: false,
      rows,
      form: {
        range,
      },
    };
  },
  computed: {
    ...mapGetters('monitorTypes', ['mtMap']),
    columns(): Array<any> {
      let me = this;
      let mtMap = this.mtMap as Map<string, MonitorType>;
      let ret = [
        {
          key: 'monitorType',
          label: '測項',
          sortable: true,
        },
        {
          key: 'startTime',
          label: '開始時間',
          sortable: true,
          formatter: (v: number) => moment(v).format('lll'),
        },
        {
          key: 'endTime',
          label: '結束時間',
          sortable: true,
          formatter: (v: number) => moment(v).format('lll'),
        },
        {
          key: 'zero_val',
          label: '零點讀值',
          sortable: true,
          tdClass: function (
            v: number | null,
            _key: string,
            item: CalibrationJSON,
          ) {
            return { 'text-danger': !me.getZeroStatus(item) };
          },
          formatter: function (
            v: number | null,
            _key: string,
            item: CalibrationJSON,
          ) {
            if (v !== null) {
              let value = v as number;
              return value.toFixed(me.mtMap.get(item.monitorType).prec);
            } else {
              return '-';
            }
          },
        },
        {
          key: 'zero_law',
          label: '零點偏移法規值',
          formatter: function (
            v: number | null,
            _key: string,
            item: CalibrationJSON,
          ) {
            if (mtMap.has(item.monitorType)) {
              let mtCase = mtMap.get(item.monitorType) as MonitorType;
              if (mtCase.zd_law !== undefined) {
                return mtCase.zd_law.toFixed(
                  me.mtMap.get(item.monitorType).prec,
                );
              } else return '-';
            } else return '-';
          },
        },
        {
          key: 'span_val',
          label: '全幅讀值',
          sortable: true,
          tdClass: function (
            v: number | null,
            _key: string,
            item: CalibrationJSON,
          ) {
            return { 'text-danger': !me.getSpanStatus(item) };
          },
          formatter: function (v: number, key: string, item: CalibrationJSON) {
            if (v !== null) {
              let value = v as number;
              return value.toFixed(me.mtMap.get(item.monitorType).prec);
            } else {
              return '-';
            }
          },
        },
        {
          key: 'span_std',
          label: '全幅標準值',
          sortable: true,
          formatter: function (v: number, key: string, item: CalibrationJSON) {
            if (v !== null) {
              let value = v as number;
              return value.toFixed(me.mtMap.get(item.monitorType).prec);
            } else {
              return '-';
            }
          },
        },
        {
          key: 'span_dev',
          label: '偏移率(%)',
          sortable: true,
          tdClass: function (
            v: number | null,
            _key: string,
            item: CalibrationJSON,
          ) {
            return { 'text-danger': !me.getSpanStatus(item) };
          },
          formatter: function (
            _v: number,
            _key: string,
            item: CalibrationJSON,
          ) {
            if (
              item.span_std !== undefined &&
              item.span_std !== 0 &&
              item.span_val !== undefined
            ) {
              let v = Math.abs(
                ((item.span_val - item.span_std) / item.span_std) * 100,
              );
              return v.toFixed(2);
            } else {
              return '-';
            }
          },
        },
        {
          key: 'span_dev_law',
          label: '偏移率法規值(%)',
          sortable: true,
          formatter: function (
            _v: number,
            _key: string,
            item: CalibrationJSON,
          ) {
            if (mtMap.has(item.monitorType)) {
              let mtCase = mtMap.get(item.monitorType) as MonitorType;
              if (mtCase.span_dev_law !== undefined) {
                return mtCase.span_dev_law.toFixed(2);
              } else return '-';
            } else return '-';
          },
        },
        {
          key: 'm',
          label: 'M值',
          formatter: function (
            _v: number,
            _key: string,
            item: CalibrationJSON,
          ) {
            if (
              item.zero_val !== undefined &&
              item.span_val !== undefined &&
              item.span_std !== undefined
            ) {
              if (item.span_val - item.zero_val !== 0) {
                let m = item.span_std / (item.span_val - item.zero_val);
                return m.toFixed(2);
              }
            }

            return '-';
          },
        },
        {
          key: 'b',
          label: 'B值',
          formatter: function (
            _v: number,
            _key: string,
            item: CalibrationJSON,
          ) {
            if (
              item.zero_val !== undefined &&
              item.span_val !== undefined &&
              item.span_std !== undefined
            ) {
              if (item.span_val - item.zero_val !== 0) {
                let b =
                  (-item.zero_val * item.span_std) /
                  (item.span_val - item.zero_val);
                return b.toFixed(2);
              }
            }

            return '-';
          },
        },
        {
          key: 'success',
          label: '校正狀態',
          tdClass: function (
            v: number | null,
            _key: string,
            item: CalibrationJSON,
          ) {
            return {
              'text-danger': !me.getStatus(item),
              'text-success': me.getStatus(item),
            };
          },
          formatter: function (
            _v: number,
            _key: string,
            item: CalibrationJSON,
          ) {
            if (me.getStatus(item)) return '成功';
            else return '失敗';
          },
        },
      ];
      return ret;
    },
  },
  mounted() {
    this.fetchMonitorTypes();
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    async query() {
      try {
        const url = `/CalibrationRecord/${this.form.range[0]}/${this.form.range[1]}`;
        const res = await axios.get(url);
        const ret = res.data;
        this.rows = ret;
      } catch (err) {
        throw new Error('failed');
      } finally {
        this.display = true;
      }
    },
    getZeroStatus(item: CalibrationJSON): boolean {
      let mtMap = this.mtMap as Map<string, MonitorType>;
      let mtCase = mtMap.get(item.monitorType) as MonitorType;
      if (mtCase.zd_law === undefined || item.zero_val === undefined)
        return true;

      return Math.abs(item.zero_val) < Math.abs(mtCase.zd_law);
    },
    getSpanStatus(item: CalibrationJSON): boolean {
      let mtMap = this.mtMap as Map<string, MonitorType>;
      let mtCase = mtMap.get(item.monitorType) as MonitorType;
      if (
        mtCase.span_dev_law !== undefined &&
        item.span_val !== undefined &&
        item.span_std !== undefined
      ) {
        // eslint-disable-next-line camelcase
        let span_dev = Math.abs(
          ((item.span_val - item.span_std) / item.span_std) * 100,
        );
        // eslint-disable-next-line camelcase
        return span_dev < mtCase.span_dev_law;
      } else return true;
    },
    getStatus(item: CalibrationJSON): boolean {
      return this.getZeroStatus(item) && this.getSpanStatus(item);
    },
    async downloadExcel() {
      const baseUrl =
        process.env.NODE_ENV === 'development' ? 'http://localhost:9000/' : '/';

      const url = `${baseUrl}Excel/CalibrationRecord/${this.form.range[0]}/${this.form.range[1]}`;

      window.open(url);
    },
  },
});
</script>
