<template>
  <b-row class="match-height">
    <b-col lg="3" md="12">
      <b-card title="健康度">
        <div id="healthPie"></div>
      </b-card>
    </b-col>
    <b-col lg="6" md="12">
      <b-card title="即時噪音">
        <div id="realtimeNoiseChart"></div>
      </b-card>
    </b-col>
    <b-col
      v-for="mt in realtimeMtList"
      :key="`realtime${mt}`"
      cols="12"
      md="6"
      lg="4"
      xl="3"
    >
      <b-card
        :header="`${getMtName(mt)}即時圖`"
        header-bg-variant="success"
        header-text-variant="white"
      >
        <div :id="`realtime_${mt}`">尚無資料</div>
      </b-card>
    </b-col>
  </b-row>
</template>
<script lang="ts">
import Vue from 'vue';
import { mapActions, mapGetters, mapState } from 'vuex';
import axios from 'axios';
import { MonitorType, MonitorTypeStatus } from './types';
import highcharts from 'highcharts';
import darkTheme from 'highcharts/themes/dark-unica';
import useAppConfig from '../@core/app-config/useAppConfig';
import moment from 'moment';

export default Vue.extend({
  data() {
    const fields = [
      {
        key: 'desp',
        label: '測項',
        sortable: true,
      },
      {
        key: 'value',
        label: '測值',
        formatter: (value: string, key: string, item: MonitorTypeStatus) => {
          const v = parseFloat(item.value);
          if (isNaN(v)) return `-`;
          else return `${item.value}`;
        },
        tdClass: (value: string, key: string, item: MonitorTypeStatus) => {
          return item.classStr;
        },
        sortable: true,
      },
      {
        key: 'unit',
        label: '單位',
      },
      {
        key: 'status',
        label: '狀態',
      },
    ];
    let chart: any;
    chart = null;
    let realtimeMtList = ['TEMP', 'HUMID', 'PRESS', 'WD_SPEED', 'WD_DIR'];
    let currentTime = moment().format('lll');
    return {
      maxPoints: 30,
      fields,
      refreshTimer: 0,
      realTimeStatus: Array<MonitorTypeStatus>(),
      realtimeCharts: Array<highcharts.Chart>(),
      realtimeChartSeries: Array<Array<highcharts.SeriesOptionsType>>(),
      chart,
      chartSeries: Array<highcharts.SeriesOptionsType>(),
      currentTime,
      realtimeMtList,
    };
  },
  computed: {
    ...mapState('monitors', ['activeID']),
    ...mapState('user', ['userInfo']),
    ...mapGetters('monitorTypes', ['mtMap']),
    skin(): any {
      const { skin } = useAppConfig();
      return skin;
    },
  },
  async mounted() {
    const { skin } = useAppConfig();
    if (skin.value == 'dark') {
      darkTheme(highcharts);
    }

    await this.fetchMonitorTypes();
    await this.getUserInfo();
    const me = this;

    this.drawHealthPie();
    await this.initRealtimeChart();
  },
  beforeDestroy() {
    clearInterval(this.refreshTimer);
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors']),
    ...mapActions('user', ['getUserInfo']),
    async refresh(): Promise<void> {
      this.plotLatestData();
      this.currentTime = moment().format('lll');
    },
    async plotLatestData(): Promise<void> {
      await this.getRealtimeStatus();
      const now = new Date().getTime();
      let chart = this.chart as highcharts.Chart;
      for (const mtStatus of this.realTimeStatus) {
        let mt = this.mtMap.get(mtStatus._id) as MonitorType;

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

        for (let rtChart of this.realtimeCharts) {
          const series = rtChart.series.find(s => {
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
      }
      chart.redraw();
      for (let rtChart of this.realtimeCharts) rtChart.redraw();
    },
    async getRealtimeStatus(): Promise<void> {
      const ret = await axios.get('/MonitorTypeStatusList');
      this.realTimeStatus = ret.data;
    },
    async initRealtimeChart(): Promise<any> {
      await this.getRealtimeStatus();

      for (const mtStatus of this.realTimeStatus) {
        let mt = this.mtMap.get(mtStatus._id) as MonitorType;

        if (mt.acoustic !== true) continue;

        let data = Array<{ x: number; y: number }>();
        const wind = ['WD_SPEED', 'WD_DIR'];
        const selectedMt = ['LeqZ'];
        const visible = selectedMt.indexOf(mtStatus._id) !== -1;
        if (mt.spectrum !== true) {
          let series: highcharts.SeriesSplineOptions = {
            id: mtStatus._id,
            name: mtStatus.desp,
            type: 'spline',
            data: data,
            tooltip: {
              valueDecimals: this.mtMap.get(mtStatus._id).prec,
            },
            visible,
          };
          this.chartSeries.push(series);
        } else {
          let series: highcharts.SeriesColumnOptions = {
            name: mtStatus.desp,
            type: 'column',
            data,
            tooltip: {
              valueDecimals: this.mtMap.get(mtStatus._id).prec,
            },
            visible,
          };
          this.chartSeries.push(series);
        }
      }

      for (let mt of this.realtimeMtList) {
        let mtInfo = this.mtMap.get(mt);
        if (mtInfo == undefined) continue;

        const visible = true;
        let data = Array<{ x: number; y: number }>();
        let series: highcharts.SeriesSplineOptions = {
          id: mt,
          name: mtInfo.desp,
          type: 'spline',
          data: data,
          tooltip: {
            valueDecimals: mtInfo.prec,
          },
          visible,
        };
        this.realtimeChartSeries.push([series]);
      }

      const me = this;
      let allP = Array<Promise<boolean>>();
      let p1: Promise<boolean> = new Promise(function (resolve, reject) {
        const chartOption: highcharts.Options = {
          chart: {
            type: 'spline',
            marginRight: 10,
            height: 300,
            events: {
              load: () => resolve(true),
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
          yAxis: {
            title: {
              text: 'value',
            },
            plotLines: [
              {
                value: 0,
                width: 1,
                color: '#808080',
              },
            ],
          },
          time: {
            timezoneOffset: -480,
          },
          exporting: {
            enabled: false,
          },
          series: me.chartSeries,
        };

        if (me.chartSeries.length !== 0)
          me.chart = highcharts.chart('realtimeNoiseChart', chartOption);
      });
      allP.push(p1);
      let i = 0;
      for (let mt of this.realtimeMtList) {
        allP.push(this.getRealtimeMt(mt, i));
        i++;
      }
      try {
        const ret = await Promise.all(allP);
        me.refreshTimer = setInterval(() => {
          me.refresh();
        }, 1000);
      } catch (err) {
        throw new Error(err);
      }
    },
    getRealtimeMt(mt: string, index: number): Promise<boolean> {
      let me = this;
      let mtInfo = this.mtMap.get(mt) as MonitorType;
      return new Promise(function (resolve, reject) {
        const chartOption: highcharts.Options = {
          chart: {
            type: 'spline',
            marginRight: 10,
            height: 300,
            events: {
              load: () => resolve(true),
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
            text: `${mtInfo.desp}`,
          },
          xAxis: {
            type: 'datetime',
            tickPixelInterval: 150,
          },
          yAxis: {
            title: {
              text: `${mtInfo.desp} (${mtInfo.unit})`,
            },
            plotLines: [
              {
                value: 0,
                width: 1,
                color: '#808080',
              },
            ],
          },
          time: {
            timezoneOffset: -480,
          },
          exporting: {
            enabled: false,
          },
          series: me.realtimeChartSeries[index],
        };
        if (me.realtimeChartSeries[index].length !== 0)
          me.realtimeCharts.push(
            highcharts.chart(`realtime_${mt}`, chartOption),
          );
      });
    },
    async drawHealthPie() {
      const chartOption: highcharts.Options = {
        title: undefined,
        chart: {
          type: 'pie',
        },
        plotOptions: {
          pie: {
            allowPointSelect: true,
            cursor: 'pointer',
            dataLabels: {
              enabled: true,
              format: '<b>{point.name}</b>: {point.percentage:.1f} %',
            },
          },
        },
        series: [
          {
            name: 'Brands',
            type: 'pie',
            colorByPoint: true,
            data: [
              {
                name: '健康度',
                y: 95,
                sliced: true,
              },
              {
                name: '未知度',
                y: 5,
              },
            ],
          },
        ],
        credits: {
          enabled: false,
          href: 'http://www.wecc.com.tw/',
        },
        exporting: {
          enabled: false,
        },
      };
      highcharts.chart('healthPie', chartOption);
    },
    async query(mt: string) {
      const now = new Date().getTime();
      const oneHourBefore = now - 60 * 60 * 1000;
      const monitors = this.activeID;
      const url = `/HistoryTrend/${monitors}/${mt}/Min/all/${oneHourBefore}/${now}`;
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

      ret.title = undefined;

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
    getMtName(id: string): string {
      let mt = this.mtMap.get(id) as MonitorType | undefined;
      if (mt === undefined) return '';
      else return mt.desp;
    },
  },
});
</script>
