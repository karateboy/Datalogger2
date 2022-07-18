<template>
  <div>
    <b-row class="match-height">
      <b-col cols="3">
        <b-card
          class="text-center"
          header="風向"
          header-class="h1 display justify-content-center"
          border-variant="primary"
          header-bg-variant="primary"
          header-text-variant="white"
        >
          <div v-if="winDirImg !== ''" class="flex-grow-1 align-content-center">
            <b-img v-if="winDirImg !== ''" :src="winDirImg" fluid></b-img>
            <h1>{{ winDirText }}</h1>
          </div>
          <h1 v-else>無資料</h1>
        </b-card>
      </b-col>
      <b-col cols="9">
        <b-row>
          <b-col cols="6">
            <b-card
              class="text-center"
              header="陣風"
              header-class="h1 display justify-content-center"
              border-variant="primary"
              header-bg-variant="primary"
              header-text-variant="white"
            >
              <b-row class="pt-2"
                ><b-col cols="2" class="bg-info"
                  ><h1>{{ getWindLevel(weatherSummary.winmax) }}級</h1></b-col
                ><b-col cols="10"
                  ><h1>{{ weatherSummary.winmax }} m/s</h1></b-col
                ></b-row
              >
            </b-card>
          </b-col>
          <b-col cols="6"
            ><b-card
              class="text-center"
              header="溫度"
              header-class="h1 display justify-content-center"
              border-variant="primary"
              header-bg-variant="primary"
              header-text-variant="white"
            >
              <div class="pt-2">
                <h1>{{ weatherSummary.temp }}℃</h1>
              </div>
            </b-card></b-col
          >
          <b-col cols="6">
            <b-card
              class="text-center"
              header="平均風力"
              header-class="h1 display justify-content-center"
              border-variant="primary"
              header-bg-variant="primary"
              header-text-variant="white"
            >
              <b-row class="pt-2"
                ><b-col cols="2" class="bg-info"
                  ><h1>{{ getWindLevel(weatherSummary.winspeed) }}級</h1></b-col
                ><b-col cols="10"
                  ><h1>{{ weatherSummary.winspeed }} m/s</h1></b-col
                ></b-row
              >
            </b-card>
          </b-col>
          <b-col cols="6">
            <b-card
              class="text-center"
              header="濕度"
              header-class="h1 display justify-content-center"
              border-variant="primary"
              header-bg-variant="primary"
              header-text-variant="white"
            >
              <div class="pt-2">
                <h1>{{ weatherSummary.humid }}%</h1>
              </div>
            </b-card>
          </b-col>
        </b-row>
      </b-col>
    </b-row>
    <b-row class="match-height">
      <b-col cols="6">
        <b-card
          class="text-center"
          header="即時雨量"
          header-class="h1 display justify-content-center"
          border-variant="primary"
          header-bg-variant="primary"
          header-text-variant="white"
          no-body
        >
          <b-table-simple>
            <b-tr>
              <b-td class="bg-info"><h3>10分鐘累積雨量</h3></b-td>
            </b-tr>
            <b-tr>
              <b-td
                ><h3>{{ weatherSummary.rain[0] }}mm</h3></b-td
              >
            </b-tr>
            <b-tr>
              <b-td class="bg-info"><h3>1小時累積雨量</h3></b-td>
            </b-tr>
            <b-tr>
              <b-td
                ><h3>{{ weatherSummary.rain[1] }}mm</h3></b-td
              >
            </b-tr>
            <b-tr>
              <b-td class="bg-info"><h3>日累積雨量</h3></b-td>
            </b-tr>
            <b-tr>
              <b-td
                ><h3>{{ weatherSummary.rain[2] }}mm</h3></b-td
              >
            </b-tr>
          </b-table-simple>
        </b-card>
      </b-col>
      <b-col cols="6">
        <b-card
          class="text-center"
          header="整點雨量"
          header-class="h1 display justify-content-center"
          border-variant="primary"
          header-bg-variant="primary"
          header-text-variant="white"
          no-body
        >
          <b-table-simple>
            <b-tr>
              <b-td class="bg-info"
                ><h3>{{ getHourStr(0) }}</h3></b-td
              >
            </b-tr>
            <b-tr>
              <b-td
                ><h3>{{ weatherSummary.hourRain[0] }}mm</h3></b-td
              >
            </b-tr>
            <b-tr>
              <b-td class="bg-info"
                ><h3>{{ getHourStr(1) }}</h3></b-td
              >
            </b-tr>
            <b-tr>
              <b-td
                ><h3>{{ weatherSummary.hourRain[1] }}mm</h3></b-td
              >
            </b-tr>
            <b-tr>
              <b-td class="bg-info"
                ><h3>{{ getHourStr(2) }}</h3></b-td
              >
            </b-tr>
            <b-tr>
              <b-td
                ><h3>{{ weatherSummary.hourRain[2] }}mm</h3></b-td
              >
            </b-tr>
          </b-table-simple>
        </b-card>
      </b-col>
      <b-col v-for="mt in windRoseList" :key="`rose${mt}`" cols="12" md="3">
        <b-card
          :header="`${getMtName(mt)}玫瑰圖`"
          header-class="h1 display justify-content-center"
          border-variant="success"
          header-bg-variant="success"
          header-text-variant="white"
        >
          <div :id="`rose_${mt}`">尚無資料</div>
        </b-card>
      </b-col>
    </b-row>
  </div>
</template>
<style scoped></style>
<script lang="ts">
import Vue from 'vue';
import { mapActions, mapGetters, mapState } from 'vuex';
import axios from 'axios';
import useAppConfig from '../@core/app-config/useAppConfig';
import { isNumber } from 'highcharts';
import highcharts from 'highcharts';
import highchartMore from 'highcharts/highcharts-more';
import { MonitorType } from './types';
import moment from 'moment';

interface WeatherSummary {
  windir?: number;
  winmax?: number;
  temp?: number;
  winspeed?: number;
  humid?: number;
  rain: Array<number | undefined>;
  hourStart: number;
  hourRain: Array<number>;
}
export default Vue.extend({
  data() {
    let weatherSummary: WeatherSummary = {
      windir: undefined,
      winmax: undefined,
      winspeed: undefined,
      humid: undefined,
      rain: [undefined, undefined, undefined],
      hourStart: 0,
      hourRain: [1, 2, 3],
    };
    return {
      refreshTimer: 0,
      weatherSummary,
    };
  },
  computed: {
    ...mapState('user', ['userInfo']),
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
    winDirImg(): string {
      if (isNumber(this.weatherSummary.windir)) {
        let v = this.weatherSummary.windir as number;
        let index = Math.floor((v + 11.25) / 22.5) % 16;
        return process.env.NODE_ENV === 'production'
          ? `/dist/windir${index}.png`
          : `/windir${index}.png`;
      } else return '';
    },
    winDirText(): string {
      if (isNumber(this.weatherSummary.windir)) {
        let dir = [
          '北',
          '北北東',
          '東北',
          '東北東',
          '東',
          '東南東',
          '東南',
          '南南東',
          '南',
          '南南西',
          '西南',
          '西南西',
          '西',
          '西北西',
          '西北',
          '北北西',
        ];
        let v = this.weatherSummary.windir as number;
        let index = Math.floor((v + 11.25) / 22.5) % 16;
        return dir[index];
      } else return '';
    },
  },
  async mounted() {
    const { skin } = useAppConfig();
    await this.fetchMonitorTypes();
    await this.getUserInfo();
    const me = this;
    this.getWeatherSummary();
    for (const mt of me.windRoseList) me.queryWindRose(mt);
    this.refreshTimer = setInterval(() => {
      me.getWeatherSummary();
      for (const mt of me.windRoseList) me.queryWindRose(mt);
    }, 60000);
  },
  beforeDestroy() {
    clearInterval(this.refreshTimer);
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors']),
    ...mapActions('user', ['getUserInfo']),
    async getWeatherSummary() {
      try {
        const res = await axios.get('/WeatherSummary');
        if (res.status === 200) {
          this.weatherSummary = res.data;
        }
      } catch (err) {
        throw err;
      }
    },
    getWindLevel(v: number | undefined): number {
      if (v == undefined) return 0;

      if (v < 0.3) return 0;
      else if (v < 1.6) return 1;
      else if (v < 3.4) return 2;
      else if (v < 5.5) return 3;
      else if (v < 8) return 4;
      else if (v < 10.8) return 5;
      else if (v < 13.9) return 6;
      else if (v < 17.2) return 7;
      else if (v < 20.8) return 8;
      else if (v < 24.5) return 9;
      else if (v < 28.5) return 10;
      else if (v < 32.7) return 11;
      else return 12;
    },
    isNumber(x: any): boolean {
      return isNumber(x);
    },
    getHourStr(v: number): string {
      let start = moment(this.weatherSummary.hourStart).subtract(v, 'hour');
      let end = moment(start);
      end.add(1, 'hour');
      return `${start.hour()}-${end.hour()}時`;
    },
    getMtName(mt: string): string {
      let mtInfo = this.mtMap.get(mt) as MonitorType;
      if (mtInfo !== undefined) return mtInfo.desp;
      else return '';
    },
    async queryWindRose(mt: string) {
      const now = new Date().getTime();
      const oneHourBefore = now - 60 * 60 * 1000;
      const monitors = 'me';

      try {
        const url = `/WindRose/me/${mt}/min/16/${oneHourBefore}/${now}`;
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
  },
});
</script>
