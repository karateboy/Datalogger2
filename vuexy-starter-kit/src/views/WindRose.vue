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
                :reduce="mt => mt._id"
                :options="monitorOfNoEPA"
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group
              label="測項 (需先定義分級)"
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
            <small class="text-danger">{{ errorMsg }}</small>
          </b-col>
          <b-col cols="12">
            <b-form-group label="方位" label-for="nWay" label-cols-md="3">
              <v-select
                id="nWay"
                v-model="form.nWay"
                label="txt"
                :reduce="dt => dt"
                :options="nWays"
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
                type="datetime"
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
              :disabled="!canQuery"
              @click="query"
            >
              查詢
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
      <div id="chart_container" />
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
import highchartMore from 'highcharts/highcharts-more';
import { MonitorType } from './types';

export default Vue.extend({
  components: {
    DatePicker,
  },
  directives: {
    Ripple,
  },

  data() {
    const range = [
      moment().startOf('days').subtract(1, 'days').valueOf(),
      moment().startOf('days').valueOf(),
    ];
    let monitor: string | undefined;
    let monitorType: string = 'me';
    let nWays = [8, 16, 32];
    let errorMsg: string = '';
    return {
      display: false,
      form: {
        monitor,
        monitorType,
        nWay: 8,
        range,
      },
      nWays,
      errorMsg,
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),    
    ...mapGetters('monitorTypes', ['mtMap', 'activatedMonitorTypes']),
    ...mapGetters('monitors', ['monitorOfNoEPA']),
    canQuery(): boolean {
      if (this.form.monitorType == undefined) return false;

      return true;
    },
    levelMt(): Array<MonitorType> {
      return this.monitorTypes.filter(
        (mt: MonitorType) => mt.levels !== undefined,
      );
    },
  },
  async mounted() {
    const { skin } = useAppConfig();
    if (skin.value == 'dark') {
      darkTheme(highcharts);
    }

    await this.fetchMonitorTypes();
    await this.fetchMonitors();

    if (this.activatedMonitorTypes.length !== 0) {
      this.form.monitorType = this.activatedMonitorTypes[0]._id;
    }

    if (this.monitorOfNoEPA.length !== 0) {
      this.form.monitor = this.monitorOfNoEPA[0]._id;
    }
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors']),
    ...mapMutations(['setLoading']),
    async query() {
      this.setLoading({ loading: true });
      this.display = true;

      try {
        const url = `/WindRose/${this.form.monitor}/${this.form.monitorType}/hour/${this.form.nWay}/${this.form.range[0]}/${this.form.range[1]}`;
        const res = await axios.get(url);
        const ret = res.data;
        ret.pane = {
          size: '90%',
        };

        ret.legend = {
          align: 'right',
          verticalAlign: 'top',
          y: 100,
          layout: 'vertical',
        };
        ret.yAxis = {
          min: 0,
          endOnTick: false,
          showLastLabel: true,
          title: {
            text: '頻率 (%)',
          },
          labels: {
            formatter(this: any) {
              return this.value + '%';
            },
          },
          reversedStacks: false,
        };

        ret.tooltip = {
          valueDecimals: 2,
          valueSuffix: '%',
        };

        ret.plotOptions = {
          series: {
            stacking: 'normal',
            shadow: false,
            groupPadding: 0,
            pointPlacement: 'on',
          },
        };

        ret.credits = {
          enabled: false,
          href: 'http://www.wecc.com.tw/',
        };

        ret.title.x = -70;
        highchartMore(highcharts);
        highcharts.chart('chart_container', ret);
      } catch (err) {
        this.$bvModal.msgBoxOk('沒有資料');
      } finally {
        this.setLoading({ loading: false });
      }
    },
  },
});
</script>
