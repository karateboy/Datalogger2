<template>
  <b-row class="match-height">
    <b-col v-if="isRealtimeMeasuring" lg="9" md="12">
      <b-card
        class="text-center"
        header="即時監測資訊"
        header-class="h4 display text-center"
        border-variant="primary"
        header-bg-variant="primary"
        header-text-variant="white"
      >
        <div id="realtimeChart"></div>
      </b-card>
    </b-col>
    <b-col v-if="isRealtimeMeasuring" lg="3" class="text-center">
      <b-card no-body border-variant="primary">
        <b-table
          :fields="fields"
          :items="realTimeStatus"
          small
          head-variant="light"
          head-row-variant="success"
          responsive
          :sticky-header="true"
          :no-border-collapse="true"
          style="max-height: 500px"
        >
          <template #cell(index)="data">
            {{ data.index + 1 }}
          </template>
        </b-table>
      </b-card>
    </b-col>
    <b-col
      v-if="cdxConfig.enable"
      cols="12"
      md="6"
      lg="6"
      xl="6"
      style="max-height: 400px"
    >
      <b-table
        small
        striped
        responsive
        sticky-header
        :fields="cdxUploadColumns"
        :items="cdxUploadLogs"
        :tbody-tr-class="rowClass"
      />
    </b-col>
    <b-col
      v-for="mt in userInfo.monitorTypeOfInterest"
      :key="mt"
      cols="12"
      md="6"
      lg="4"
      xl="3"
    >
      <b-card border-variant="primary">
        <div :id="`history_${mt}`"></div>
      </b-card>
    </b-col>
    <b-col
      v-for="mt in windRoseList"
      :key="`rose${mt}`"
      cols="12"
      md="6"
      lg="4"
      xl="3"
    >
      <b-card
        :header="`${getMtName(mt)}玫瑰圖`"
        header-class="h4 display text-center"
        border-variant="success"
        header-bg-variant="success"
        header-text-variant="white"
      >
        <div :id="`rose_${mt}`">尚無資料</div>
      </b-card>
    </b-col>
  </b-row>
</template>
<script lang="ts">
import Vue from 'vue';
import { mapActions, mapGetters, mapState } from 'vuex';
import axios from 'axios';
import { MonitorType, MonitorTypeStatus, CdxConfig } from './types';
import highcharts from 'highcharts';
import darkTheme from 'highcharts/themes/dark-unica';
import useAppConfig from '../@core/app-config/useAppConfig';
import highchartMore from 'highcharts/highcharts-more';
import moment from 'moment';

export default Vue.extend({
  data() {
    const fields = [
      {
        key: 'index',
        label: '序號',
      },
      {
        key: 'desp',
        label: '測項',
      },
      {
        key: 'value',
        label: '測值',
        formatter: (value: string, key: string, item: MonitorTypeStatus) => {
          const v = parseFloat(item.value);
          if (isNaN(v)) return `-`;
          else return `${item.value}`;
        },
      },
      {
        key: 'unit',
        label: '單位',
      },
      {
        key: 'status',
        label: '狀態',
        tdClass: (value: string, key: string, item: MonitorTypeStatus) => {
          return item.classStr;
        },
      },
    ];
    let chart: any;
    chart = null;
    const cdxUploadColumns = [
      {
        key: 'time',
        label: '時間',
        sortable: true,
        formatter: (v: number) => moment(v).format('lll'),
      },
      {
        key: 'level',
        label: '等級',
        sortable: true,
        formatter: (v: number) => {
          switch (v) {
            case 1:
              return '資訊';

            case 2:
              return '警告';

            case 3:
              return '錯誤';
          }
        },
        tdClass: (v: number) => {
          switch (v) {
            case 1:
              return 'success';

            case 2:
              return 'warning';

            case 3:
              return 'danger';
          }
        },
      },
      {
        key: 'info',
        label: '詳細資訊',
        sortable: true,
      },
    ];
    let cdxConfig: CdxConfig = {
      enable: false,
      user: '',
      password: '',
      siteCounty: '',
      siteID: '',
    };
    return {
      maxPoints: 30,
      fields,
      refreshTimer: 0,
      mtInterestTimer: 0,
      realTimeStatus: Array<MonitorTypeStatus>(),
      chartSeries: Array<highcharts.SeriesOptionsType>(),
      chart,
      cdxConfig,
      cdxUploadColumns,
      cdxUploadLogs: [],
    };
  },
  computed: {
    ...mapState('user', ['userInfo']),
    ...mapState('monitors', ['monitors', 'activeID']),
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapGetters('monitorTypes', ['mtMap']),
    skin() {
      const { skin } = useAppConfig();
      return skin;
    },
    windRoseList(): Array<string> {
      let mtInterest = this.userInfo.monitorTypeOfInterest as Array<string>;
      let ret = mtInterest.filter(mt => mt !== 'WD_DIR');
      return ret;
    },
    isRealtimeMeasuring(): boolean {
      return this.realTimeStatus.length !== 0;
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

    const me = this;
    for (const mt of this.userInfo.monitorTypeOfInterest) this.query(mt);
    for (const mt of me.windRoseList) me.queryWindRose(mt);

    this.mtInterestTimer = setInterval(() => {
      for (const mt of me.userInfo.monitorTypeOfInterest) me.query(mt);
      for (const mt of me.windRoseList) me.queryWindRose(mt);
    }, 60000);

    await this.getCdxConfig();
    await this.initRealtimeChart();
  },
  beforeDestroy() {
    clearInterval(this.refreshTimer);
    clearInterval(this.mtInterestTimer);
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors', 'getActiveID']),
    ...mapActions('user', ['getUserInfo']),
    async refresh(): Promise<void> {
      this.plotLatestData();
      this.getCdxUploadEvents();
    },
    async plotLatestData(): Promise<void> {
      await this.getRealtimeStatus();
      const now = new Date().getTime();

      let chart = this.chart as highcharts.Chart;
      for (const mtStatus of this.realTimeStatus) {
        const series = chart.series.find(s => {
          return s.name === mtStatus.desp;
        });

        if (series) {
          let value = parseFloat(mtStatus.value);
          if (!isNaN(value)) {
            series.addPoint([now, value], false, false, true);
            while (series.data.length >= this.maxPoints) {
              series.removePoint(0, false);
            }
          }
        }
      }

      chart.redraw();
    },
    async getRealtimeStatus(): Promise<void> {
      const ret = await axios.get('/MonitorTypeStatusList');
      this.realTimeStatus = ret.data;
    },
    async initRealtimeChart(): Promise<boolean> {
      await this.getRealtimeStatus();

      if (this.realTimeStatus.length === 0) return false;

      let yAxisList = Array<highcharts.YAxisOptions>();
      let yAxisMap = new Map<string, number>();
      for (const mtStatus of this.realTimeStatus) {
        let data = Array<{ x: number; y: number }>();
        //data.push({ x: 1, y: 1 });
        const wind = ['WD_DIR'];
        const selectedMt = Array<string>();
        let monitorTypes = this.monitorTypes as Array<MonitorType>;
        let activeMonitorTypes = monitorTypes.filter(mt => {
          if (mt.measuringBy && Array.isArray(mt.measuringBy)) {
            return mt.measuringBy.length !== 0;
          } else return false;
        });

        if (activeMonitorTypes.length !== 0)
          selectedMt.push(activeMonitorTypes[0]._id);

        const visible = selectedMt.indexOf(mtStatus._id) !== -1;
        if (wind.indexOf(mtStatus._id) === -1) {
          let yAxisIndex: number;
          if (yAxisMap.has(mtStatus.unit)) {
            yAxisIndex = yAxisMap.get(mtStatus.unit) as number;
          } else {
            yAxisList.push({
              title: {
                text: mtStatus.unit,
              },
              showEmpty: false,
            });
            yAxisIndex = yAxisList.length - 1;
            yAxisMap.set(mtStatus.unit, yAxisIndex);
          }

          let series: highcharts.SeriesSplineOptions = {
            id: mtStatus._id,
            name: mtStatus.desp,
            type: 'spline',
            data: data,
            tooltip: {
              valueDecimals: this.mtMap.get(mtStatus._id).prec,
            },
            yAxis: yAxisIndex,
            visible,
          };
          this.chartSeries.push(series);
        } else {
          let series: highcharts.SeriesScatterOptions = {
            name: mtStatus.desp,
            type: 'scatter',
            data,
            tooltip: {
              valueDecimals: this.mtMap.get(mtStatus._id).prec,
            },
            visible,
          };
          this.chartSeries.push(series);
        }
      }
      // Make last yAxis oppsite
      //yAxisList[yAxisList.length - 1].opposite = true;
      //console.log(yAxisList);

      const me = this;
      const pointFormatter = function pointFormatter(this: any) {
        const d = new Date(this.x);
        return `${d.toLocaleString()}:${Math.round(this.y)}度`;
      };
      return new Promise(function (resolve, reject) {
        const chartOption: highcharts.Options = {
          chart: {
            type: 'spline',
            marginRight: 10,
            //height: 300,
            events: {
              load: () => {
                me.refreshTimer = setInterval(() => {
                  me.refresh();
                }, 3000);
                resolve(true);
              },
            },
          },
          navigation: {
            buttonOptions: {
              enabled: true,
            },
          },
          credits: {
            enabled: false,
          },

          title: {
            text: '',
          },
          xAxis: {
            type: 'datetime',
            tickPixelInterval: 150,
          },
          yAxis: yAxisList,
          time: {
            timezoneOffset: -480,
          },
          exporting: {
            enabled: false,
          },
          plotOptions: {
            scatter: {
              tooltip: {
                pointFormatter,
              },
            },
          },
          series: me.chartSeries,
        };
        me.chart = highcharts.chart('realtimeChart', chartOption);
      });
    },
    async query(mt: string) {
      const now = new Date().getTime();
      const oneHourBefore = now - 60 * 60 * 1000;
      const url = `/HistoryTrend/${this.activeID}/${mt}/Min/all/${oneHourBefore}/${now}`;
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

      let mtInfo = this.mtMap.get(mt) as MonitorType;
      ret.title!.text = `${mtInfo.desp}分鐘趨勢圖`;

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
            valueDecimals: this.mtMap.get(mt).prec,
          },
        },
        scatter: {
          tooltip: {
            valueDecimals: this.mtMap.get(mt).prec,
          },
        },
      };
      ret.time = {
        timezoneOffset: -480,
      };
      ret.exporting = {
        enabled: false,
      };
      highcharts.chart(`history_${mt}`, ret);
    },
    getMtName(mt: string): string {
      let mtInfo = this.mtMap.get(mt) as MonitorType;
      if (mtInfo !== undefined) return mtInfo.desp;
      else return '';
    },
    async queryWindRose(mt: string) {
      const now = new Date().getTime();
      const oneHourBefore = now - 60 * 60 * 1000;

      try {
        const url = `/WindRose/${this.activeID}/${mt}/min/16/${oneHourBefore}/${now}`;
        const res = await axios.get(url);
        const ret = res.data;
        ret.pane = {
          size: '90%',
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

        ret.exporting = {
          enabled: false,
        };
        ret.credits = {
          enabled: false,
          href: 'http://www.wecc.com.tw/',
        };

        ret.title.x = -70;
        highchartMore(highcharts);
        highcharts.chart(`rose_${mt}`, ret);
      } catch (err) {
      } finally {
      }
    },
    async getCdxUploadEvents() {
      try {
        const range = [
          moment().subtract(7, 'days').valueOf(),
          moment().valueOf(),
        ];
        let src = 'S:CDX';
        let res = await axios.get(`/Alarms/${src}/1/${range[0]}/${range[1]}`);
        if (res.status === 200) {
          this.cdxUploadLogs = res.data.slice(0, 5);
        }
      } catch (err) {
        throw new Error(`$err`);
      }
    },
    async getCdxConfig() {
      try {
        let ret = await axios.get('/CdxConfig');
        if (ret.status === 200) {
          this.cdxConfig = ret.data;
        }
      } catch (err) {
        throw new Error(`$err`);
      }
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
  },
});
</script>
