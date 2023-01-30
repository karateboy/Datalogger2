<template>
  <b-row v-if="userInfo.isAdmin">
    <b-col cols="12">
      <b-table
        class="text-center"
        :fields="transposeFields"
        :items="transposePowerUsageList"
        stacked="sm"
        responsive
        striped
      >
      </b-table>
    </b-col>
    <b-col v-for="m in monitorNoMe" :key="m._id" xl="3" lg="6" sm="12">
      <b-card border-variant="primary">
        <div :id="`history_${m._id}`"></div>
      </b-card>
    </b-col>
    <b-col
      v-for="m in monitorNoMe"
      :key="`compare_${m._id}`"
      xl="3"
      lg="6"
      sm="12"
    >
      <b-card border-variant="primary">
        <div :id="`year_compare_${m._id}`"></div>
      </b-card>
    </b-col>
  </b-row>
  <b-row v-else class="match-height">
    <b-col xl="3" lg="6" sm="12">
      <b-table
        :fields="fields"
        :items="powerUsageList"
        class="text-center"
        stacked="sm"
      >
      </b-table>
    </b-col>
    <b-col xl="9" lg="6" sm="12">
      <b-row>
        <b-col v-for="m in monitorNoMe" :key="m._id" cols="12">
          <b-card border-variant="primary">
            <div :id="`history_${m._id}`"></div>
          </b-card>
        </b-col>
        <b-col v-for="m in monitorNoMe" :key="`compare_${m._id}`" cols="12">
          <b-card border-variant="primary">
            <div :id="`year_compare_${m._id}`"></div>
          </b-card>
        </b-col>
      </b-row>
    </b-col>
  </b-row>
</template>
<style>
.highcharts-container,
.highcharts-container svg {
  width: 100% !important;
}
.verticaltext {
  word-wrap: break-word;
  white-space: pre-wrap;
}
</style>
<script lang="ts">
import Vue from 'vue';
import { mapActions, mapGetters, mapState } from 'vuex';
import axios from 'axios';
import { MonitorType, MonitorTypeStatus, CdxConfig } from './types';
import { Monitor } from '../store/monitors/types';
import highcharts from 'highcharts';
import darkTheme from 'highcharts/themes/dark-unica';
import useAppConfig from '../@core/app-config/useAppConfig';
import moment from 'moment';

interface PowerUsage {
  name: string;
  averageUsageLastWeek: number;
  usageToday: number;
}

export default Vue.extend({
  data() {
    const fields = [
      {
        key: 'name',
        label: '用戶名稱',
      },
      {
        key: 'averageUsageLastWeek',
        label: '上週日均用電量(度)',
        formatter: (v: number) => {
          if (isNaN(v)) return `-`;
          else return `${v.toFixed(0)}`;
        },
      },
      {
        key: 'usageToday',
        label: '本日用電量(度)',
        formatter: (v: number) => {
          if (isNaN(v)) return `-`;
          else return `${v.toFixed(0)}`;
        },
      },
    ];
    let transposeFields = Array<any>();
    let powerUsageList = Array<PowerUsage>();
    let transposePowerUsageList = Array<any>();
    return {
      maxPoints: 30,
      fields,
      powerUsageList,
      transposeFields,
      transposePowerUsageList,
      refreshTimer: 0,
      mtInterestTimer: 0,
      realTimeStatus: Array<MonitorTypeStatus>(),
      chartSeries: Array<highcharts.SeriesOptionsType>(),
    };
  },
  computed: {
    ...mapState('user', ['userInfo']),
    ...mapState('monitors', ['monitors', 'activeID']),
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapGetters('monitorTypes', ['mtMap', 'activatedMonitorTypes']),
    skin() {
      const { skin } = useAppConfig();
      return skin;
    },
    isRealtimeMeasuring(): boolean {
      return this.realTimeStatus.length !== 0;
    },
    monitorNoMe(): Array<Monitor> {
      let monitors = this.monitors as Array<Monitor>;
      return monitors.filter(m => m._id !== 'me');
    },
    widgetCols(): number {
      if (this.monitorNoMe.length === 1) return 12;
      else return 4;
    },
  },
  async mounted() {
    const { skin } = useAppConfig();
    if (skin.value == 'dark') {
      darkTheme(highcharts);
    }

    await this.fetchMonitors();
    await this.getActiveID();
    await this.fetchMonitorTypes();
    await this.getUserInfo();
    await this.getPowerUsage();

    const me = this;
    for (const m of this.monitors) {
      this.query(m);
      this.drawCompareChart(m);
    }
    this.mtInterestTimer = setInterval(() => {
      for (const m of this.monitors) {
        this.query(m);
        this.drawCompareChart(m);
      }
    }, 60000);
  },
  beforeDestroy() {
    clearInterval(this.refreshTimer);
    clearInterval(this.mtInterestTimer);
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors', 'getActiveID']),
    ...mapActions('user', ['getUserInfo']),
    async refresh(): Promise<void> {},
    async getRealtimeStatus(): Promise<void> {
      const ret = await axios.get('/MonitorTypeStatusList');
      this.realTimeStatus = ret.data;
    },
    async query(m: Monitor) {
      const now = new Date().getTime();
      const oneHourBefore = now - 24 * 60 * 60 * 1000;
      let mtList = this.activatedMonitorTypes as Array<MonitorType>;
      let mtStr = mtList.map(mt => mt._id).join(':');
      const url = `/HistoryTrend/${m._id}/POWER/Min/all/${oneHourBefore}/${now}`;
      const res = await axios.get(url);
      const ret: highcharts.Options = res.data;

      ret.chart = {
        type: 'spline',
        zoomType: 'x',
        panning: {
          enabled: true,
        },
        panKey: 'shift',
        alignTicks: false,
      };

      ret.title!.text = `${m.desc}即時用電量`;

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

      ret.exporting = {
        enabled: false,
      };
      let xAxis: highcharts.XAxisOptions = ret.xAxis as highcharts.XAxisOptions;
      xAxis.type = 'datetime';

      xAxis!.dateTimeLabelFormats = {
        day: '%b%e日',
        week: '%b%e日',
        month: '%y年%b',
      };

      ret.plotOptions = {
        spline: {
          tooltip: {
            valueDecimals: 2,
          },
        },
        scatter: {
          tooltip: {
            valueDecimals: 2,
          },
        },
      };
      ret.time = {
        timezoneOffset: -480,
      };
      ret.exporting = {
        enabled: false,
      };
      highcharts.chart(`history_${m._id}`, ret);
    },
    async drawCompareChart(m: Monitor) {
      const url = `/PowerCompareChart/${m._id}`;
      const res = await axios.get(url);
      const ret: highcharts.Options = res.data;

      ret.chart = {
        type: 'column',
        zoomType: 'x',
        panning: {
          enabled: true,
        },
        panKey: 'shift',
        alignTicks: false,
      };

      //ret.title!.text = `${m.desc}即時用電量`;

      ret.tooltip = { valueDecimals: 2 };
      ret.legend = { enabled: true };
      ret.credits = {
        enabled: false,
        href: 'http://www.wecc.com.tw/',
      };

      ret.exporting = {
        enabled: false,
      };
      /*
      let xAxis: highcharts.XAxisOptions = ret.xAxis as highcharts.XAxisOptions;
      xAxis.type = 'datetime';

      xAxis!.dateTimeLabelFormats = {
        day: '%b%e日',
        week: '%b%e日',
        month: '%y年%b',
      };
      */

      ret.plotOptions = {
        spline: {
          tooltip: {
            valueDecimals: 2,
          },
        },
        scatter: {
          tooltip: {
            valueDecimals: 2,
          },
        },
      };
      ret.time = {
        timezoneOffset: -480,
      };
      console.info(ret);
      highcharts.chart(`year_compare_${m._id}`, ret);
    },
    getMtName(mt: string): string {
      let mtInfo = this.mtMap.get(mt) as MonitorType;
      if (mtInfo !== undefined) return mtInfo.desp;
      else return '';
    },
    rowClass(item: any, type: any) {
      if (!item || type !== 'row') return;
      switch (item.level) {
        case 1:
          return 'table-success';

        case 2:
          return 'table-warning';

        case 3:
          return 'table-danger';
      }
    },
    async getPowerUsage() {
      try {
        let res = await axios.get('/PowerUsageList');
        if (res.status == 200) {
          this.powerUsageList = res.data;
          // update transposeFields
          this.transposeFields.splice(0, this.transposeFields.length);
          this.transposeFields.push({
            key: 'title',
            label: '',
          });
          let usageThisMonth: any = {
            title: '上週日均用電量(度)',
          };
          let usageToday: any = {
            title: '本日用電量(度)',
          };
          for (let entry of this.powerUsageList) {
            this.transposeFields.push({
              key: entry.name,
              label: entry.name,
            });
            usageThisMonth[entry.name] = entry.averageUsageLastWeek.toFixed(0);
            usageToday[entry.name] = entry.usageToday.toFixed(0);
          }
          this.transposePowerUsageList.push(usageThisMonth);
          this.transposePowerUsageList.push(usageToday);
        }
      } catch (err) {
        throw new Error(`${err}`);
      }
    },
  },
});
</script>
