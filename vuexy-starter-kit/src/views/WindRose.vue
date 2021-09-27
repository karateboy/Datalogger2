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
                :options="monitors"
              />
            </b-form-group>
          </b-col>
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
                :options="monitorTypes"
              />
            </b-form-group>
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
          <b-col cols="12">
            <b-form-group label="分級" label-for="level" label-cols-md="3">
              <b-input v-model.number="form.level1" type="number" />
              <b-input v-model.number="form.level2" type="number" />
              <b-input v-model.number="form.level3" type="number" />
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
import { mapState, mapActions, mapMutations } from 'vuex';
import darkTheme from 'highcharts/themes/dark-unica';
import useAppConfig from '../@core/app-config/useAppConfig';
import moment from 'moment';
import axios from 'axios';
import highcharts from 'highcharts';
import highchartMore from 'highcharts/highcharts-more';

export default Vue.extend({
  components: {
    DatePicker,
  },
  directives: {
    Ripple,
  },

  data() {
    const range = [moment().subtract(1, 'days').valueOf(), moment().valueOf()];
    let monitor: string | undefined;
    let monitorType: string = 'me';
    let nWays = [8, 16, 32];
    return {
      display: false,
      form: {
        monitor,
        monitorType,
        nWay: 8,
        range,
        level1: 5,
        level2: 10,
        level3: 15,
      },
      nWays,
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapState('monitors', ['monitors']),
  },
  watch: {},
  async mounted() {
    const { skin } = useAppConfig();
    if (skin.value == 'dark') {
      darkTheme(highcharts);
    }

    await this.fetchMonitorTypes();
    await this.fetchMonitors();

    if (this.monitorTypes.length !== 0) {
      this.form.monitorType = 'PM25';
    }

    if (this.monitors.length !== 0) {
      this.form.monitor = this.monitors[0]._id;
    }
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors']),
    ...mapMutations(['setLoading']),
    async query() {
      this.setLoading({ loading: true });
      this.display = true;
      let level1 = this.form.level1;
      let level2 = this.form.level2;
      let level3 = this.form.level3;

      try {
        const url = `/WindRose/${this.form.monitor}/${this.form.monitorType}/hour/${this.form.nWay}/${this.form.range[0]}/${this.form.range[1]}/${level1}/${level2}/${level3}`;
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
