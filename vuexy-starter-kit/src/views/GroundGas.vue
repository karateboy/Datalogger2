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
                v-model="form.monitorTypes"
                label="desp"
                :reduce="mt => mt._id"
                :options="groundMonitorTypes"
                :close-on-select="false"
                multiple
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group
              label="時間單位"
              label-for="reportUnit"
              label-cols-md="3"
            >
              <v-select
                id="reportUnit"
                v-model="form.reportUnit"
                label="txt"
                :reduce="dt => dt.id"
                :options="reportUnits"
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group
              label="資料區間"
              label-for="dataRange"
              label-cols-md="3"
            >
              <v-select
                id="dataRange"
                v-model="form.start"
                label="txt"
                :reduce="dt => dt.range[0]"
                :options="dateRanges"
              />
            </b-form-group>
          </b-col>
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
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              type="submit"
              variant="primary"
              class="mr-1"
              @click="downloadExcel"
            >
              下載Excel
            </b-button>
          </b-col>
        </b-row>
      </b-form>
    </b-card>
    <b-card v-show="display">
      <b-card-body><div id="chart_container" /></b-card-body>
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
import { mapState, mapActions, mapMutations, mapGetters } from 'vuex';
import darkTheme from 'highcharts/themes/dark-unica';
import useAppConfig from '../@core/app-config/useAppConfig';
import moment from 'moment';
import axios from 'axios';
import highcharts from 'highcharts';
import { MonitorType } from './types';

export default Vue.extend({
  directives: {
    Ripple,
  },
  data() {
    const dateRanges = [
      {
        txt: '2022/09/02',
        range: [
          moment('2022-09-02').startOf('day').valueOf(),
          moment('2022-09-02').startOf('day').add(1, 'day').valueOf(),
        ],
      },
      {
        txt: '2022/09/12',
        range: [
          moment('2022-09-12').startOf('day').valueOf(),
          moment('2022-09-12').startOf('day').add(1, 'day').valueOf(),
        ],
      },
      {
        txt: '2022/09/22',
        range: [
          moment('2022-09-22').startOf('day').valueOf(),
          moment('2022-09-22').startOf('day').add(1, 'day').valueOf(),
        ],
      },
      {
        txt: '2022/11/10',
        range: [
          moment('2022-11-10').startOf('day').valueOf(),
          moment('2022-11-10').startOf('day').add(1, 'day').valueOf(),
        ],
      },
      {
        txt: '2022/11/12',
        range: [
          moment('2022-11-12').startOf('day').valueOf(),
          moment('2022-11-12').startOf('day').add(1, 'day').valueOf(),
        ],
      },
      {
        txt: '2022/12/05',
        range: [
          moment('2022-12-05').startOf('day').valueOf(),
          moment('2022-12-05').startOf('day').add(1, 'day').valueOf(),
        ],
      },
      {
        txt: '2022/12/23',
        range: [
          moment('2022-12-23').startOf('day').valueOf(),
          moment('2022-12-23').startOf('day').add(1, 'day').valueOf(),
        ],
      },
    ];
    const range = dateRanges[0].range;
    let start = dateRanges[0].range[0];
    return {
      statusFilters: [
        { id: 'all', txt: '全部' },
        { id: 'normal', txt: '正常量測值' },
        { id: 'calbration', txt: '校正' },
        { id: 'maintance', txt: '維修' },
        { id: 'invalid', txt: '無效數據' },
        { id: 'valid', txt: '有效數據' },
      ],
      reportUnits: [
        { txt: '分', id: 'Min' },
        { txt: '十五分', id: 'FifteenMin' },
        { txt: '小時', id: 'Hour' },
      ],
      dateRanges,
      reportUnit: 'Hour',
      display: false,
      chartTypes: [
        {
          type: 'line',
          desc: '折線圖',
        },
        {
          type: 'spline',
          desc: '曲線圖',
        },
        {
          type: 'area',
          desc: '面積圖',
        },
        {
          type: 'areaspline',
          desc: '曲線面積圖',
        },
        {
          type: 'column',
          desc: '柱狀圖',
        },
        {
          type: 'scatter',
          desc: '點圖',
        },
      ],
      form: {
        monitors: ['me'],
        monitorTypes: ['CO2', 'CH4', 'O2', 'N2', 'N2-O2'],
        reportUnit: 'Min',
        statusFilter: 'all',
        chartType: 'line',
        range,
        start,
      },
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapGetters('monitorTypes', ['activatedMonitorTypes']),
    ...mapState('monitors', ['monitors']),
    myMonitors(): Array<any> {
      return this.monitors.filter((m: any) => m._id === 'me');
    },
    range(): Array<number> {
      return [this.form.start, moment(this.form.start).add(1, 'day').valueOf()];
    },
    groundMonitorTypes(): Array<MonitorType> {
      let mtList = ['CO2', 'CH4', 'O2', 'N2', 'N2-O2'];
      return this.monitorTypes.filter(
        (mt: MonitorType) => mtList.indexOf(mt._id) !== -1,
      );
    },
  },
  watch: {},
  async mounted() {
    const { skin } = useAppConfig();
    if (skin.value == 'dark') {
      darkTheme(highcharts);
    }

    await this.fetchMonitorTypes();
    await this.fetchMonitors();
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors']),
    ...mapMutations(['setLoading']),
    async query() {
      this.setLoading({ loading: true });
      this.display = true;
      const monitors = this.form.monitors.join(':');
      const url = `/HistoryTrend/${monitors}/${this.form.monitorTypes.join(
        ':',
      )}/${this.form.reportUnit}/${this.form.statusFilter}/${this.range[0]}/${
        this.range[1]
      }`;
      const res = await axios.get(url);
      const ret = res.data;

      this.setLoading({ loading: false });
      if (this.form.chartType !== 'boxplot') {
        ret.chart = {
          type: this.form.chartType,
          zoomType: 'x',
          panning: true,
          panKey: 'shift',
          alignTicks: false,
        };

        const pointFormatter = function pointFormatter(this: any) {
          const d = new Date(this.x);
          return `${d.toLocaleString()}:${Math.round(this.y)}度`;
        };

        ret.colors = [
          '#7CB5EC',
          '#434348',
          '#90ED7D',
          '#F7A35C',
          '#8085E9',
          '#F15C80',
          '#E4D354',
          '#2B908F',
          '#FB9FA8',
          '#91E8E1',
          '#7CB5EC',
          '#80C535',
          '#969696',
        ];

        ret.tooltip = { valueDecimals: 2 };
        ret.legend = { enabled: true };
        ret.credits = {
          enabled: false,
          href: 'http://www.wecc.com.tw/',
        };
        ret.xAxis.type = 'datetime';
        ret.xAxis.dateTimeLabelFormats = {
          day: '%b%e日',
          week: '%b%e日',
          month: '%y年%b',
        };

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
      }
      highcharts.chart('chart_container', ret);
    },
    async downloadExcel() {
      const baseUrl =
        process.env.NODE_ENV === 'development' ? 'http://localhost:9000/' : '/';
      const monitors = this.form.monitors.join(':');
      const url = `${baseUrl}HistoryTrend/excel/${monitors}/${this.form.monitorTypes.join(
        ':',
      )}/${this.form.reportUnit}/${this.form.statusFilter}/${this.range[0]}/${
        this.range[1]
      }`;

      window.open(url);
    },
  },
});
</script>

<style></style>
