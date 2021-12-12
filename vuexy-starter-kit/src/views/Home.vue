<template>
  <b-row class="match-height">
    <b-col lg="6" md="12">
      <b-row class="match-height">
        <b-col cols="8">
          <b-card
            img-width="250"
            img-src="../assets/images/old_house.png"
            img-left
            border-variant="primary"
          >
            <h2>太陽能總發電量:4.3 KW</h2>
            <h2>老屋總耗電量:2.4 KW</h2>
            <h2>綠能使用率: 100%</h2>
          </b-card>
        </b-col>
        <b-col cols="4">
          <b-card
            header="氣象資訊"
            header-class="h4 display text-center"
            border-variant="primary"
            header-bg-variant="white"
            :img-src="weatherUrl"
            img-bottom
            img-height="150"
            ><p>{{ weatherForecast }}</p>
          </b-card>
        </b-col>
      </b-row>
    </b-col>
    <b-col lg="6" md="12">
      <b-card
        class="text-center"
        header="供電資訊"
        header-class="h4 display text-center"
        border-variant="primary"
        header-bg-variant="white"
      >
        <b-row>
          <b-col>
            <b-table-simple borderless small>
              <b-tbody>
                <b-tr>
                  <b-td rowspan="3" colspan="2"
                    ><b-img src="../assets/images/taipower.jpg" fluid
                  /></b-td>
                </b-tr>
                <b-tr><b-td>台電</b-td></b-tr>
                <b-tr><b-td>功率5KW</b-td></b-tr>
              </b-tbody>
            </b-table-simple>
          </b-col>
          <b-col>
            <b-table-simple borderless small>
              <b-tbody>
                <b-tr>
                  <b-td rowspan="3"
                    ><b-img src="../assets/images/solar_power.png" fluid-grow
                  /></b-td>
                </b-tr>
                <b-tr><b-td>太陽能1</b-td></b-tr>
                <b-tr><b-td>功率2KW</b-td></b-tr>
              </b-tbody>
            </b-table-simple>
          </b-col>
          <b-col>
            <b-table-simple borderless small>
              <b-tbody>
                <b-tr>
                  <b-td rowspan="3"
                    ><b-img src="../assets/images/solar_power.png" fluid-grow
                  /></b-td>
                </b-tr>
                <b-tr><b-td>太陽能2</b-td></b-tr>
                <b-tr><b-td>功率2KW</b-td></b-tr>
              </b-tbody>
            </b-table-simple>
          </b-col>
        </b-row>
      </b-card>
    </b-col>
    <b-col lg="6" md="12">
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
    <b-col lg="6" class="text-center">
      <b-card
        class="text-left"
        header="用電情形"
        header-class="h4 display text-center"
        border-variant="success"
        header-bg-variant="success"
        header-text-variant="white"
      >
        <b-row class="p-1">
          <b-col>
            <b-table-simple borderless>
              <b-tbody>
                <b-tr>
                  <b-td rowspan="3"
                    ><b-img src="../assets/images/ac.png" fluid
                  /></b-td>
                </b-tr>
                <b-tr><b-td>冷氣1</b-td></b-tr>
                <b-tr><b-td>電流2A</b-td></b-tr>
              </b-tbody>
            </b-table-simple>
          </b-col>
          <b-col>
            <b-table-simple borderless>
              <b-tbody>
                <b-tr>
                  <b-td rowspan="3"
                    ><b-img src="../assets/images/ac.png" fluid
                  /></b-td>
                </b-tr>
                <b-tr><b-td>冷氣2</b-td></b-tr>
                <b-tr><b-td>電流2A</b-td></b-tr>
              </b-tbody>
            </b-table-simple>
          </b-col>
          <b-col>
            <b-table-simple borderless>
              <b-tbody>
                <b-tr>
                  <b-td rowspan="3"
                    ><b-img src="../assets/images/refregrator.png" fluid
                  /></b-td>
                </b-tr>
                <b-tr><b-td>電冰箱</b-td></b-tr>
                <b-tr><b-td>電流2A</b-td></b-tr>
              </b-tbody>
            </b-table-simple>
          </b-col>
        </b-row>
        <b-row class="p-1">
          <b-col>
            <b-table-simple borderless small>
              <b-tbody>
                <b-tr>
                  <b-td rowspan="3"
                    ><b-img src="../assets/images/plug.png" fluid
                  /></b-td>
                </b-tr>
                <b-tr><b-td>插座1</b-td></b-tr>
                <b-tr><b-td>電流2A</b-td></b-tr>
              </b-tbody>
            </b-table-simple>
          </b-col>
          <b-col>
            <b-table-simple borderless small>
              <b-tbody>
                <b-tr>
                  <b-td rowspan="3"
                    ><b-img src="../assets/images/plug.png" fluid
                  /></b-td>
                </b-tr>
                <b-tr><b-td>插座2</b-td></b-tr>
                <b-tr><b-td>電流2A</b-td></b-tr>
              </b-tbody>
            </b-table-simple>
          </b-col>
          <b-col>
            <b-table-simple borderless small>
              <b-tbody>
                <b-tr>
                  <b-td rowspan="3"
                    ><b-img src="../assets/images/other.png" fluid
                  /></b-td>
                </b-tr>
                <b-tr><b-td>其他</b-td></b-tr>
                <b-tr><b-td>電流2A</b-td></b-tr>
              </b-tbody>
            </b-table-simple>
          </b-col>
        </b-row>
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

export default Vue.extend({
  data() {
    let chart: any;
    chart = null;
    return {
      maxPoints: 30,
      refreshTimer: 0,
      mtInterestTimer: 0,
      realTimeStatus: Array<MonitorTypeStatus>(),
      chartSeries: Array<highcharts.SeriesOptionsType>(),
      chart,
      weatherForecast: '',
      weatherUrl: '',
    };
  },
  computed: {
    ...mapState('user', ['userInfo']),
    ...mapGetters('monitorTypes', ['mtMap']),
    skin() {
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
  },
});
</script>
