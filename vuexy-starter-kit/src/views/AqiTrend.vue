<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="12">
            <b-form-group label="測點" label-for="monitor" label-cols-md="3">
              <v-select
                id="monitor"
                v-model="form.monitors"
                label="desc"
                :reduce="mt => mt._id"
                :options="monitors"
                :close-on-select="false"
                multiple
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group
              label="統計單位"
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
              label="圖表類型"
              label-for="chartType"
              label-cols-md="3"
            >
              <v-select
                id="chartType"
                v-model="form.chartType"
                label="desc"
                :reduce="ct => ct.type"
                :options="chartTypes"
              />
            </b-form-group>
          </b-col>
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
<style>
.highcharts-container,
.highcharts-container svg {
  width: 100% !important;
}
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

export default Vue.extend({
  components: {
    DatePicker,
  },
  directives: {
    Ripple,
  },

  data() {
    const range = [
      moment().subtract(7, 'days').startOf('day').valueOf(),
      moment().valueOf(),
    ];
    return {
      reportUnits: [
        { txt: '小時AQI', id: 'Hour' },
        { txt: '日AQI', id: 'Day' },
      ],
      reportUnit: 'Day',
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
        monitors: Array<string>(),
        reportUnit: 'Day',
        chartType: 'line',
        range,
      },
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapGetters('monitorTypes', ['activatedMonitorTypes']),
    ...mapState('monitors', ['monitors']),
  },
  watch: {},
  async mounted() {
    const { skin } = useAppConfig();
    if (skin.value == 'dark') {
      darkTheme(highcharts);
    }

    await this.fetchMonitors();

    if (this.monitors.length !== 0) {
      this.form.monitors.push(this.monitors[0]._id);
    }
  },
  methods: {
    ...mapActions('monitors', ['fetchMonitors']),
    ...mapMutations(['setLoading']),
    async query() {
      this.setLoading({ loading: true });
      try {
        this.display = true;
        const monitors = this.form.monitors.join(':');
        let isDailyAqi = true;
        if (this.form.reportUnit === 'Hour') isDailyAqi = false;

        const url = `/AqiTrend/${monitors}/${isDailyAqi}/${this.form.range[0]}/${this.form.range[1]}`;
        const res = await axios.get(url);
        const ret = res.data;
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
      } catch (err) {
        console.error(err);
        this.$bvModal.show(`查詢失敗 ${err}`);
      } finally {
        this.setLoading({ loading: false });
      }
    },
    async downloadExcel() {
      const baseUrl =
        process.env.NODE_ENV === 'development' ? 'http://localhost:9000/' : '/';
      const monitors = this.form.monitors.join(':');
      let isDailyAqi = true;
      if (this.form.reportUnit === 'Hour') isDailyAqi = false;

      const url = `${baseUrl}AqiTrend/excel/${monitors}/${isDailyAqi}/${this.form.range[0]}/${this.form.range[1]}`;
      window.open(url);
    },
  },
});
</script>

<style></style>
