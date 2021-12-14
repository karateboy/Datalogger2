<template>
  <b-row class="match-height">
    <b-col lg="6" md="12">
      <b-row class="match-height">
        <b-col cols="8"
          ><h1 class="pl-1">{{ currentTime }}</h1>
          <b-card
            img-src="../assets/images/old_house.png"
            img-fluid
            img-left
            img-height="200"
            border-variant="primary"
            class="text-right p-2"
          >
            <div class="p-2">
              <h2>太陽能總發電量: {{ totalPowerSupply }} kVA</h2>
              <h2>老屋總耗電量: {{ totalPowerUsage }} kVA</h2>
              <h2>綠能使用率: {{ greenPercentage }}%</h2>
            </div>
          </b-card>
        </b-col>
        <b-col cols="4">
          <b-card
            header="氣象資訊"
            header-class="h3 display text-center"
            border-variant="primary"
            img-bottom
            img-height="100"
            :img-src="weatherUrl"
            header-bg-variant="primary"
            header-text-variant="white"
          >
            <h3 class="p-1">{{ weatherForecast }}</h3>
          </b-card>
        </b-col>
      </b-row>
    </b-col>
    <b-col lg="6" md="12">
      <b-card
        header="供電資訊"
        class="text-center"
        header-class="h3 display text-center"
        border-variant="success"
        header-bg-variant="success"
        header-text-variant="white"
      >
        <b-row class="pt-3">
          <b-col>
            <b-card
              img-src="/images/taipower.png"
              img-width="120"
              img-fluid
              img-left
              title="台電"
            >
              <h2>{{ taipowerSupply }} KVA</h2>
            </b-card>
          </b-col>
          <b-col v-for="power in powerSupplyList" :key="power.mt">
            <b-card
              :img-src="power.img"
              img-width="120"
              img-fluid
              img-left
              :title="getMtName(power.mt)"
            >
              <h2>{{ getEquipmentPower(power.mt) }}</h2>
            </b-card>
          </b-col>
        </b-row>
      </b-card>
    </b-col>
    <b-col lg="6" md="12">
      <b-card
        class="text-center"
        header="即時監測資訊"
        header-class="h3 display text-center"
        border-variant="primary"
        header-bg-variant="primary"
        header-text-variant="white"
        no-body
      >
        <div id="realtimeChart"></div>
      </b-card>
    </b-col>
    <b-col lg="6">
      <b-card
        header="用電情形"
        class="text-left"
        header-class="h3 display text-center"
        border-variant="success"
        header-bg-variant="success"
        header-text-variant="white"
      >
        <b-row class="p-2" style="">
          <b-col v-for="power in powerConsumptionList" :key="power.mt" cols="3">
            <b-card :img-src="power.img" img-height="120" img-left no-body>
              <b-card-body
                class="p-1"
                :title="getMtName(power.mt)"
                :sub-title="getEquipmentPower(power.mt)"
              />
            </b-card>
          </b-col>
        </b-row>
      </b-card>
    </b-col>
  </b-row>
</template>
<style scoped>
.center {
  margin: auto;
  width: 50%;
  border: 3px solid green;
  padding: 10px;
}
</style>
<script lang="ts">
import Vue from 'vue';
import { mapActions, mapGetters, mapState } from 'vuex';
import axios from 'axios';
import { MonitorType, MonitorTypeStatus } from './types';
import highcharts from 'highcharts';
import darkTheme from 'highcharts/themes/dark-unica';
import useAppConfig from '../@core/app-config/useAppConfig';
import moment from 'moment';

interface PowerEquipment {
  mt: string;
  img: string;
}
export default Vue.extend({
  data() {
    let chart: any;
    chart = null;
    let currentTime = moment().format('lll');
    let powerSupplyList = Array<PowerEquipment>(
      {
        mt: 'V1',
        img: '/images/solar_power.png',
      },
      {
        mt: 'V2',
        img: '/images/solar_power.png',
      },
    );
    let powerConsumptionList: Array<PowerEquipment> = [
      {
        mt: 'V3',
        img: '/images/ac.png',
      },
      {
        mt: 'V4',
        img: '/images/ac.png',
      },
      {
        mt: 'V5',
        img: '/images/refregrator.png',
      },
      {
        mt: 'V6',
        img: '/images/plug.png',
      },
      {
        mt: 'V7',
        img: '/images/plug.png',
      },
      {
        mt: 'V8',
        img: '/images/plug.png',
      },
      {
        mt: 'V9',
        img: '/images/plug.png',
      },
      {
        mt: 'V10',
        img: '/images/light_bulb.png',
      },
    ];
    return {
      maxPoints: 30,
      refreshTimer: 0,
      mtInterestTimer: 0,
      currentTime,
      realTimeStatus: Array<MonitorTypeStatus>(),
      chartSeries: Array<highcharts.SeriesOptionsType>(),
      chart,
      weatherForecast: '',
      weatherUrl: '',
      powerSupplyList,
      powerConsumptionList,
    };
  },
  computed: {
    ...mapState('user', ['userInfo']),
    ...mapGetters('monitorTypes', ['mtMap']),
    skin(): any {
      const { skin } = useAppConfig();
      return skin;
    },
    totalPowerSupply(): number {
      let sum = this.realTimeStatus
        .filter(rt => rt._id === 'V1' || rt._id === 'V2')
        .map(r => {
          let ret = parseFloat(r.value);
          if (isNaN(ret)) return 0;
          else return ret;
        });

      return sum.length !== 0 ? sum.reduce((a, b) => a + b) : 0;
    },
    totalPowerUsage(): number {
      let sum1 = this.realTimeStatus
        .filter(rt => rt._id === 'V3' || rt._id === 'V4' || rt._id === 'V5')
        .map(r => {
          let ret = parseFloat(r.value);
          if (isNaN(ret)) return 0;
          else return ret;
        });

      let sum2 = this.realTimeStatus
        .filter(
          r =>
            r._id === 'V6' ||
            r._id === 'V7' ||
            r._id === 'V8' ||
            r._id === 'V9' ||
            r._id === 'V10',
        )
        .map(r => {
          let ret = parseFloat(r.value);
          if (isNaN(ret)) return 0;
          else return ret;
        });
      if (sum1.length !== 0 && sum2.length !== 0)
        return sum1.reduce((a, b) => a + b) + sum2.reduce((a, b) => a + b);
      else return 0;
    },
    taipowerSupply(): number {
      if (this.totalPowerUsage >= this.totalPowerSupply)
        return this.totalPowerUsage - this.totalPowerSupply;
      else return 0;
    },
    greenPercentage(): string {
      if (this.totalPowerUsage == 0) return '0';
      else
        return ((this.totalPowerSupply / this.totalPowerUsage) * 100).toFixed(
          0,
        );
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
    await this.getWeatherReport();
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
      this.currentTime = moment().format('lll');
      this.plotLatestData();
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

      let yAxisList = Array<highcharts.YAxisOptions>();
      let yAxisMap = new Map<string, number>();
      const selectedMt = this.realTimeStatus.map(mt => mt._id);
      for (const mtStatus of this.realTimeStatus) {
        let data = Array<{ x: number; y: number }>();
        //data.push({ x: 1, y: 1 });
        const wind = ['WD_SPEED', 'WD_DIR'];
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
    async getWeatherReport() {
      try {
        const resp = await axios.get('/WeatherReport');
        if (resp.status === 200) {
          this.weatherForecast = '';
          let weatherElements: Array<any> =
            resp.data.records.locations[0].location[0].weatherElement;
          let desc = weatherElements.find(
            p => p.elementName === 'WeatherDescription',
          );
          if (desc !== undefined) {
            this.weatherForecast += desc.time[0].elementValue[0].value;
          }

          let wx = weatherElements.find(p => p.elementName === 'Wx');
          if (wx !== undefined) {
            this.weatherUrl = `https://www.cwb.gov.tw/V8/assets/img/weather_icons/weathers/svg_icon/day/${wx.time[0].elementValue[1].value}.svg`;
          }
        }
      } catch (err) {
        throw new Error('fail to get weather report');
      }
    },
    getMtName(mt: string) {
      let mtCase: MonitorType = this.mtMap.get(mt);
      if (mtCase !== undefined) return mtCase.desp;
      else return '';
    },
    getEquipmentPower(mt: string) {
      let mtEntry = this.realTimeStatus.find(entry => entry._id === mt);
      if (mtEntry !== undefined) {
        return `${mtEntry.value} ${mtEntry.unit}`;
      } else return 'N/A';
    },
  },
});
</script>
