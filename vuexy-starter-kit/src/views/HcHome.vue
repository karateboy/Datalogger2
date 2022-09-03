<template>
  <div>
    <b-row class="mt-1 match-height">
      <b-col lg="5" md="6" sm="12">
        <b-row no-gutters align-v="center" align-content="stretch">
          <b-col cols="6">
            <b-card
              class="text-center"
              header-html="平均風力&nbsp;<sub>近一小時</sub>"
              header-class="h1 display justify-content-center font-weight-bolder"
              border-variant="primary"
              header-bg-variant="primary"
              header-text-variant="white"
              no-body
            >
              <b-row align-v="center" align-h="center" class="p-3">
                <b-col lg="2" md="4" sm="6"
                  ><h1>{{ getWindLevel(weatherSummary.winSpeed) }}級</h1></b-col
                >
                <b-col lg="10" md="8" sm="6"
                  ><h1>
                    {{ formatValue(weatherSummary.winSpeed) }} m/s
                  </h1></b-col
                >
                <b-col cols="12"
                  ><b-progress
                    :value="getWindLevel(weatherSummary.winSpeed)"
                    :max="10"
                    animated
                    show-value
                    height="2rem"
                  ></b-progress
                ></b-col>
              </b-row>
            </b-card>
          </b-col>
          <b-col cols="6">
            <b-card
              class="text-center"
              header-html="最大風力&nbsp;<sub>本日最大</sub>"
              header-class="h1 display justify-content-center font-weight-bolder"
              border-variant="primary"
              header-bg-variant="primary"
              header-text-variant="white"
              no-body
            >
              <b-row align-v="center" align-h="center" class="p-3">
                <b-col lg="2" md="4" sm="6"
                  ><h1>
                    {{ getWindLevel(weatherSummary.winSpeedMaxToday) }}級
                  </h1></b-col
                >
                <b-col lg="10" md="8" sm="6"
                  ><h1>
                    {{ formatValue(weatherSummary.winSpeedMaxToday) }} m/s
                  </h1></b-col
                >
                <b-col cols="12"
                  ><b-progress
                    :value="getWindLevel(weatherSummary.winSpeedMaxToday)"
                    :max="10"
                    animated
                    show-value
                    height="2rem"
                  ></b-progress
                ></b-col>
              </b-row>
            </b-card>
          </b-col>
          <b-col cols="6">
            <b-card
              class="text-center"
              header-html="即時陣風&nbsp;<sub>更新週期(5秒)</sub>"
              header-class="h1 display justify-content-center font-weight-bolder"
              border-variant="primary"
              header-bg-variant="primary"
              header-text-variant="white"
              no-body
            >
              <b-row align-v="center" align-h="center" class="p-3">
                <b-col lg="2" md="4" sm="6"
                  ><h1>
                    {{ getWindLevel(getRealtimeValue('WINSPEED_MAX')) }}級
                  </h1></b-col
                >
                <b-col lg="10" md="8" sm="6"
                  ><h1>{{ getRealtimeValueStr('WINSPEED_MAX') }} m/s</h1></b-col
                >
                <b-col cols="12"
                  ><b-progress
                    :value="getWindLevel(getRealtimeValue('WINSPEED_MAX'))"
                    :max="10"
                    animated
                    show-value
                    height="2rem"
                  ></b-progress
                ></b-col>
              </b-row>
            </b-card>
          </b-col>
          <b-col cols="6">
            <b-card
              class="text-center"
              header-html="最大陣風&nbsp;<sub>本日最大</sub>"
              header-class="h1 display justify-content-center font-weight-bolder"
              border-variant="primary"
              header-bg-variant="primary"
              header-text-variant="white"
              no-body
            >
              <b-row align-v="center" align-h="center" class="p-3">
                <b-col lg="2" md="4" sm="6"
                  ><h1>
                    {{ getWindLevel(weatherSummary.winMaxToday) }}級
                  </h1></b-col
                >
                <b-col lg="10" md="8" sm="6"
                  ><h1>
                    {{ formatValue(weatherSummary.winMaxToday) }} m/s
                  </h1></b-col
                >
                <b-col cols="12"
                  ><b-progress
                    :value="getWindLevel(weatherSummary.winMaxToday)"
                    :max="10"
                    animated
                    show-value
                    height="2rem"
                  ></b-progress
                ></b-col>
              </b-row>
            </b-card>
          </b-col>
        </b-row>
      </b-col>
      <b-col lg="2" md="6" sm="12">
        <b-card
          class="text-center"
          header-html="風向&nbsp;<sub>更新週期(5秒)</sub>"
          header-class="h1 justify-content-center font-weight-bolder"
          border-variant="primary"
          header-bg-variant="primary"
          header-text-variant="white"
          no-body
        >
          <b-row
            align-h="center"
            align-content="stretch"
            class="m-2"
            no-gutters
          >
            <b-col cols="12" class="border rounded-circle border-primary"
              ><b-img
                v-if="winDirImg !== ''"
                :src="winDirImg"
                class="p-1"
                fluid-grow
              ></b-img>
            </b-col>
            <b-col class="mt-1"
              ><h1 class="display">
                <strong>{{ winDirText }}</strong>
              </h1></b-col
            >
          </b-row>
        </b-card>
      </b-col>
      <b-col lg="5" md="6" sm="12">
        <b-row>
          <b-col cols="12"
            ><b-card
              class="text-center"
              header-html="溫度&nbsp;<sub>更新週期(5秒)"
              header-class="h1 display justify-content-center font-weight-bolder"
              border-variant="primary"
              header-bg-variant="primary"
              header-text-variant="white"
              no-body
            >
              <b-row align-v="center" align-h="center" class="p-3">
                <b-col cols="12"
                  ><h1>{{ getRealtimeValueStr('TEMP') }}℃</h1></b-col
                >
                <b-col cols="12"
                  ><b-progress
                    :value="getRealtimeValue('TEMP')"
                    :max="50"
                    show-value
                    animated
                    height="2rem"
                  ></b-progress
                ></b-col>
              </b-row> </b-card
          ></b-col>

          <b-col cols="12">
            <b-card
              class="text-center"
              header-html="濕度&nbsp;<sub>更新週期(5秒)"
              header-class="h1 justify-content-center font-weight-bolder"
              border-variant="primary"
              header-bg-variant="primary"
              header-text-variant="white"
            >
              <b-row align-v="center" align-h="center" class="p-3">
                <b-col cols="12"
                  ><h1>{{ getRealtimeValueStr('HUMID') }}%</h1></b-col
                >
                <b-col cols="12"
                  ><b-progress
                    :value="getRealtimeValue('HUMID')"
                    :max="100"
                    show-value
                    animated
                    height="2rem"
                  ></b-progress
                ></b-col>
              </b-row>
            </b-card>
          </b-col>
        </b-row>
      </b-col>
      <b-col md="6" sm="12">
        <b-card
          class="text-center"
          header-html="即時雨量&nbsp;<sub>更新週期(1分鐘)"
          header-class="h1 justify-content-center font-weight-bolder"
          border-variant="primary"
          header-bg-variant="primary"
          header-text-variant="white"
          no-body
        >
          <b-row no-gutters>
            <b-col cols="12" class="bg-secondary p-1"
              ><h3>10分鐘累積雨量</h3></b-col
            >
            <b-col cols="12" class="p-1"
              ><h3>{{ weatherSummary.rain[0] }}mm</h3></b-col
            >
            <b-col cols="12" class="bg-secondary p-1"
              ><h3>1小時累積雨量</h3></b-col
            >
            <b-col cols="12" class="p-1"
              ><h3>{{ weatherSummary.rain[1] }}mm</h3></b-col
            >
            <b-col cols="12" class="bg-secondary p-1"
              ><h3>日累積雨量</h3></b-col
            >
            <b-col cols="12" class="p-1"
              ><h3>{{ weatherSummary.rain[2] }}mm</h3></b-col
            >
          </b-row>
        </b-card>
      </b-col>
      <b-col md="6" sm="12">
        <b-card
          class="text-center"
          header-html="整點雨量&nbsp;<sub>更新週期(1小時)"
          header-class="h1 justify-content-center font-weight-bolder"
          border-variant="primary"
          header-bg-variant="primary"
          header-text-variant="white"
          no-body
        >
          <b-row no-gutters>
            <b-col v-for="hr in hourGroups[0]" :key="hr" lg="3" md="4" sm="6">
              <h3 class="bg-secondary p-1">{{ getHourStr(hr) }}</h3>
              <h3 class="p-1">{{ getHourRain(hr) }}mm</h3>
            </b-col>
            <b-col v-for="hr in hourGroups[1]" :key="hr" lg="3" md="4" sm="6">
              <h3 class="bg-secondary p-1">{{ getHourStr(hr) }}</h3>
              <h3 class="p-1">{{ getHourRain(hr) }}mm</h3>
            </b-col>
            <b-col v-for="hr in hourGroups[2]" :key="hr" lg="3" md="4" sm="6">
              <h3 class="bg-secondary p-1">{{ getHourStr(hr) }}</h3>
              <h3 class="p-1">{{ getHourRain(hr) }}mm</h3>
            </b-col>
          </b-row>
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
import { MonitorType } from './types';
import moment from 'moment';
interface MtRecord {
  mtName: string;
  value?: number;
  status: string;
}

interface WeatherSummary {
  windir?: number;
  winMax?: number;
  winMaxToday?: number;
  temp?: number;
  winSpeed?: number;
  winSpeedMaxToday?: number;
  humid?: number;
  rain: Array<number | undefined>;
  hourStart: number;
  hourRain: Array<number>;
}
export default Vue.extend({
  data() {
    let realtime = Array<MtRecord>();
    let weatherSummary: WeatherSummary = {
      rain: [undefined, undefined, undefined],
      hourStart: 0,
      hourRain: [1, 2, 3],
    };
    let hourGroups = Array<Array<number>>();
    for (let i = 0; i < 12; i++) {
      if (i % 4 === 0) {
        hourGroups.push(Array<number>());
      }
      let groupIdx = Math.floor(i / 4);
      let hrGroup = hourGroups[groupIdx];
      hrGroup.push(i);
    }

    return {
      refreshTimer: 0,
      weatherSummary,
      realtime,
      count: 0,
      hourGroups,
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
    winDirImg(): string {
      let v = this.getRealtimeValue('WD_DIR');
      if (typeof v == 'number') {
        let index = Math.floor((v + 11.25) / 22.5) % 16;
        return process.env.NODE_ENV === 'production'
          ? `/dist/windir${index}.svg`
          : `/windir${index}.svg`;
      } else return '';
    },
    winDirText(): string {
      let v = this.getRealtimeValue('WD_DIR');
      if (typeof v == 'number') {
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
    this.getRealtimeWeather();

    this.refreshTimer = setInterval(() => {
      me.count++;
      me.getWeatherSummary();
      me.getRealtimeWeather();
    }, 3000);
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
    async getRealtimeWeather() {
      try {
        const res = await axios.get('/RealtimeWeather');
        if (res.status === 200) {
          this.realtime = res.data;
        }
      } catch (err) {
        throw err;
      }
    },
    getRealtimeValue(mt: string): number {
      let ret = this.realtime.find(mtRecord => mtRecord.mtName === mt);
      if (ret?.value === undefined) return 0;
      else return ret?.value;
    },
    getRealtimeValueStr(mt: string): string {
      let ret = this.realtime.find(mtRecord => mtRecord.mtName === mt);
      if (ret?.value === undefined) return '0.0';
      else return ret?.value?.toFixed(1);
    },
    formatValue(v: number | undefined): string {
      if (v) return v.toFixed(1);

      return '0.0';
    },
    getWindLevel(v: number | undefined): number {
      if (v === undefined) return 0;

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
    getHourRain(hr: number): string {
      if (hr < this.weatherSummary.hourRain.length)
        return `${this.weatherSummary.hourRain[hr]}`;
      else return '-';
    },
  },
});
</script>
