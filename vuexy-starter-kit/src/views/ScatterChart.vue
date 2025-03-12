<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="6">
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
          <b-col cols="6">
            <b-form-group label="測項" label-for="monitorType" label-cols-md="3">
              <v-select
                  id="monitorType"
                  v-model="form.monitorTypes"
                  label="desp"
                  :reduce="mt => mt._id"
                  :options="activatedMonitorTypes"
                  :close-on-select="false"
                  multiple
              />
            </b-form-group>
          </b-col>
          <b-col cols="6">
            <b-form-group label="資料種類" label-for="dataType" label-cols-md="3">
              <v-select
                  id="dataType"
                  v-model="form.dataType"
                  label="txt"
                  :reduce="dt => dt.id"
                  :options="dataTypes"
              />
            </b-form-group>
          </b-col>
          <b-col cols="6">
            <b-form-group
                label="回歸趨勢模式"
                label-for="regressionModes"
                label-cols-md="3"
            >
              <v-select
                  id="regressionModes"
                  v-model="form.regressionSettings.type"
                  label="txt"
                  :reduce="dt => dt.id"
                  :options="regressionModes"
              />
            </b-form-group>
          </b-col>
          <b-col cols="6">
            <b-form-group label="狀態" label-for="statusFilter" label-cols-md="3">
              <v-select
                  id="statusFilter"
                  v-model="form.statusFilter"
                  label="txt"
                  :reduce="dt => dt.id"
                  :options="statusFilters"
              />
            </b-form-group>
          </b-col>
          <b-col cols="6">
            <b-form-group label="資料區間" label-for="dataRange" label-cols-md="3">
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
          <b-col cols="6" class="text-center">
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              type="submit"
              variant="primary"
              class="mr-1"
              @click="query"
            >
              查詢
            </b-button>
          </b-col>
        </b-row>
      </b-form>
    </b-card>
    <b-card v-show="display">
      <b-card-body>
        <div id="chart_container"></div>
      </b-card-body>
    </b-card>
  </div>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';

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
const jStat = require('jstat');
const highchartsRegression = require('highcharts-regression');
highchartsRegression(highcharts);

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
      moment().minute(0).second(0).millisecond(0).valueOf(),
    ];
    let regressionSettings: any = {
      type: 'linear',
      name: '%eq R^2:%r2 標準差:%se',
    };
    return {
      statusFilters: [
        { id: 'all', txt: '全部' },
        { id: 'normal', txt: '正常量測值' },
        { id: 'calibration', txt: '校正' },
        { id: 'maintenance', txt: '維修' },
        { id: 'invalid', txt: '無效數據' },
        { id: 'valid', txt: '有效數據' },
        { id: 'validWithoutEngineExhaustion', txt: '有效數據(不含引擎排放)' },
        { id: 'engineExhaustion', txt: '引擎排放' },
      ],
      regressionModes: [
        { id: 'linear', txt: '線性' },
        { id: 'exponential', txt: 'exponential指數' },
        { id: 'polynomial', txt: 'polynomial多項式' },
        { id: 'power', txt: 'power' },
        { id: 'logarithmic', txt: 'logarithmic對數式' },
        { id: 'loess', txt: 'loess' },
      ],
      display: false,
      form: {
        monitors: Array<string>(),
        monitorTypes: Array<string>(),
        dataType: 'hour',
        statusFilter: 'all',
        range,
        regressionSettings,
      },
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapGetters('monitorTypes', ['activatedMonitorTypes', 'mtMap']),
    ...mapState('monitors', ['monitors']),
    ...mapGetters('tables', ['dataTypes']),
  },
  watch: {},
  async mounted() {
    const { skin } = useAppConfig();
    if (skin.value == 'dark') {
      darkTheme(highcharts);
    }

    await this.fetchMonitorTypes();
    await this.fetchMonitors();
    await this.fetchTables();

    if (this.activatedMonitorTypes.length !== 0)
      this.form.monitorTypes.push(this.activatedMonitorTypes[0]._id);

    if (this.monitors.length !== 0) {
      this.form.monitors.push(this.monitors[0]._id);
    }
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors']),
    ...mapActions('tables', ['fetchTables']),
    ...mapMutations(['setLoading']),
    async query() {
      if (this.form.monitorTypes.length !== 2) {
        this.$bvModal.msgBoxOk('測項數必須為2個');
        return;
      }
      this.setLoading({ loading: true });
      try {
        this.display = true;
        const monitors = this.form.monitors.join(':');
        const url = `/ScatterChart/${monitors}/${this.form.monitorTypes.join(
          ':',
        )}/${this.form.dataType}/${this.form.statusFilter}/${
          this.form.range[0]
        }/${this.form.range[1]}`;
        const res = await axios.get(url);
        const ret = res.data;
        let mt1 = this.mtMap.get(this.form.monitorTypes[0]);
        let mt2 = this.mtMap.get(this.form.monitorTypes[1]);
        ret.plotOptions = {
          scatter: {
            marker: {
              radius: 3,
              states: {
                hover: {
                  enabled: true,
                  lineColor: 'rgb(100,100,100)',
                },
              },
            },
            states: {
              hover: {
                marker: {
                  enabled: false,
                },
              },
            },
            tooltip: {
              headerFormat: '<b>{series.name}</b><br>',
              pointFormat: `${mt1.desp}:{point.x} ${mt1.unit}, ${mt2.desp}:{point.y} ${mt2.unit}`,
            },
          },
        };

        let prec = Math.max(mt1.prec, mt2.prec);

        ret.tooltip = { valueDecimals: prec };
        ret.credits = {
          enabled: false,
          href: 'http://www.wecc.com.tw/',
        };
        let series = ret.series as Array<any>;
        if (series[0].data.length !== 0) {
          let combinedIndex = series.length - 1;
          series[combinedIndex].regression = true;
          series[combinedIndex].visible = false;
          series[combinedIndex].regressionSettings = {
            type: this.form.regressionSettings.type,
            name: '%eq R^2:%r2',
          };
          highcharts.chart('chart_container', ret);
        } else this.$bvModal.msgBoxOk('沒有足夠資料');
      } catch (err) {
        throw Error(`${err}`);
      } finally {
        this.setLoading({ loading: false });
      }
    },
  },
});
</script>

<style></style>
