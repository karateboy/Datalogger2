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
          <b-col offset-md="3">
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
      <div id="zero_chart" />
      <div id="span_chart" />
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
import moment from 'moment';
import axios from 'axios';
import {mapActions, mapGetters} from 'vuex';
import highcharts from 'highcharts';
import {MonitorType} from "@/views/types";

const Ripple = require('vue-ripple-directive');

const excel = require('../libs/excel');
const _ = require('lodash');

interface CalibrationJSON {
  monitorType: string;
  startTime: number;
  endTime: number;
  zero_val?: number;
  span_std?: number;
  span_val?: number;
  zero_success?: boolean;
  span_success?: boolean;
  point3?: number;
  point3_success?: boolean;
  point4?: number;
  point4_success?: boolean;
  point5?: number | null;
  point5_success?: boolean;
  point6?: number;
  point6_success?: boolean;
}

export default Vue.extend({
  components: {
    DatePicker,
  },
  directives: {
    Ripple,
  },

  data() {
    const range = [
      moment().subtract(1, 'days').minute(0).second(0).millisecond(0).valueOf(),
      moment().minute(0).second(0).millisecond(0).add(1, 'hours').valueOf(),
    ];
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
      return [
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
            return {
              'text-success': item.zero_success === true,
              'text-danger': item.zero_success === false,
            };
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
          key: 'span_val',
          label: '全幅讀值',
          sortable: true,
          tdClass: function (
              v: number | null,
              _key: string,
              item: CalibrationJSON,
          ) {
            return {
              'text-success': item.span_success === true,
              'text-danger': item.span_success === false,
            };
          },
          formatter: function (
              v: number | null,
              key: string,
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
          key: 'point3',
          label: '校正點3',
          sortable: true,
          tdClass: function (
              v: number | null,
              _key: string,
              item: CalibrationJSON,
          ) {
            return {
              'text-success': item.point3_success === true,
              'text-danger': item.point3_success === false,
            };
          },
          formatter: function (
              v: number | null,
              key: string,
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
          key: 'point4',
          label: '校正點4',
          sortable: true,
          tdClass: function (
              v: number | null,
              _key: string,
              item: CalibrationJSON,
          ) {
            return {
              'text-success': item.point4_success === true,
              'text-danger': item.point4_success === false,
            };
          },
          formatter: function (
              v: number | null,
              key: string,
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
          key: 'point5',
          label: '校正點5',
          sortable: true,
          tdClass: function (
              v: number | null,
              _key: string,
              item: CalibrationJSON,
          ) {
            return {
              'text-success': item.point5_success === true,
              'text-danger': item.point5_success === false,
            };
          },
          formatter: function (
              v: number | null,
              key: string,
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
          key: 'point6',
          label: '校正點6',
          sortable: true,
          tdClass: function (
              v: number | null,
              _key: string,
              item: CalibrationJSON,
          ) {
            return {
              'text-success': item.point6_success === true,
              'text-danger': item.point6_success === false,
            };
          },
          formatter: function (
              v: number | null,
              key: string,
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
                return m.toFixed(6);
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
                return b.toFixed(6);
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
    },
  },
  mounted() {
    this.fetchMonitorTypes();
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    chartAdjust(ret: highcharts.Options) {
      ret.chart = {
        type: 'spline',
        zoomType: 'x',
        panning: {
          enabled: true,
          type: 'x',
        },
        panKey: 'shift',
        alignTicks: false,
        borderColor: '#000000',
        plotBorderColor: '#000000',
      };

      const pointFormatter = function pointFormatter(this: any) {
        const d = new Date(this.x);
        return `${d.toLocaleString()}:${Math.round(this.y)}度`;
      };

      ret.tooltip = { valueDecimals: 2 };
      ret.legend = { enabled: true };
      ret.credits = {
        enabled: false,
        href: 'http://www.wecc.com.tw/',
      };
      let xAxis = ret.xAxis as highcharts.XAxisOptions;
      xAxis.type = 'datetime';
      xAxis.dateTimeLabelFormats = {
        day: '%b%e日',
        week: '%b%e日',
        month: '%Y年%b',
      };
      xAxis.gridLineColor = '#666666';
      xAxis.lineColor = '#000000';
      xAxis.tickColor = '#000000';
      xAxis.labels = {
        style: {
          color: '#000000',
          fontSize: '1rem',
        },
      };
      let yAxisArray = ret.yAxis as Array<highcharts.YAxisOptions>;
      for (let yAxis of yAxisArray) {
        //yAxis.max = (typeof this.form.YMax) === "number" ? this.form.YMax : undefined;
        //yAxis.min = this.form.YMin;
        yAxis.gridLineColor = '#666666';
        yAxis.lineColor = '#000000';
        yAxis.tickColor = '#000000';
        yAxis.labels = {
          style: {
            color: '#000000',
            fontSize: '1rem',
          },
        };
      }
      ret.plotOptions = {
        scatter: {
          tooltip: {
            pointFormatter,
          },
        },
      };
      ret.time = {
        timezoneOffset: -480,
      };
      for (let s of ret.series as Array<highcharts.SeriesOptionsType>) {
        s.visible = false;
      }
    },
    async query() {
      try {
        const url = `/CalibrationRecord/${this.form.range[0]}/${this.form.range[1]}`;
        const res = await axios.get(url);
        const ret = res.data;
        this.rows = ret.calibrations;
        this.chartAdjust(ret.spanChart);
        this.chartAdjust(ret.zeroChart);
        highcharts.chart('zero_chart', ret.zeroChart);
        highcharts.chart('span_chart', ret.spanChart);
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
      return (item.zero_success || (item.zero_success === undefined && this.getZeroStatus(item))) &&
          (item.span_success || (item.span_success === undefined && this.getSpanStatus(item))) &&
          (item.point3_success === undefined || item.point3_success) &&
          (item.point4_success === undefined || item.point4_success) &&
          (item.point5_success === undefined || item.point5_success) &&
          (item.point6_success === undefined || item.point6_success);
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
