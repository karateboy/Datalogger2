<template>
  <b-row class="match-height">
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
    <b-col v-for="data in aisData" :key="data.monitor" cols="12">
      <b-card border-variant="primary" :title="getAisTitle(data)">
        <div class="map_container">
          <GmapMap
            ref="mapRef"
            :center="getMapCenter(data)"
            :zoom="14"
            map-type-id="terrain"
            class="map_canvas"
          >
            <GmapMarker
              v-for="(m, index) in data.ships"
              :key="index"
              :position="m.position"
              :clickable="true"
              :title="m.MMSI"
            /><font-awesome-icon icon="fa-solid fa-user-secret" />
          </GmapMap>
        </div>
      </b-card>
    </b-col>
  </b-row>
</template>
<script lang="ts">
import Vue from 'vue';
import { mapActions, mapGetters, mapState } from 'vuex';
import axios from 'axios';
import { MonitorType } from './types';
import highcharts from 'highcharts';
import darkTheme from 'highcharts/themes/dark-unica';
import useAppConfig from '../@core/app-config/useAppConfig';
import highchartMore from 'highcharts/highcharts-more';
import moment from 'moment';

interface AisShip {
  MMSI: string;
  LAT: string;
  LON: string;
  SPEED: string | number;
  HEADING: string;
  COURSE: string;
  STATUS: string;
  TIMESTAMP: string;
  position?: {
    lat: number;
    lng: number;
  };
}

interface AisData {
  monitor: string;
  time: number;
  ships: Array<AisShip>;
}

export default Vue.extend({
  data() {
    let aisData = Array<AisData>();
    return {
      refreshTimer: 0,
      mtInterestTimer: 0,
      aisData,
    };
  },
  computed: {
    ...mapState('user', ['userInfo']),
    ...mapState('monitors', ['monitors']),
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapGetters('monitorTypes', ['mtMap']),
    ...mapGetters('monitors', ['mMap']),
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
    await this.fetchMonitors();
    await this.getUserInfo();
    const me = this;
    for (const mt of this.userInfo.monitorTypeOfInterest) this.query(mt);

    await this.getLatestAisData();
    this.mtInterestTimer = setInterval(() => {
      me.getLatestAisData();
      for (const mt of me.userInfo.monitorTypeOfInterest) me.query(mt);
    }, 60000);
  },
  beforeDestroy() {
    clearInterval(this.refreshTimer);
    clearInterval(this.mtInterestTimer);
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors']),
    ...mapActions('user', ['getUserInfo']),
    async refresh(): Promise<void> {},
    async query(mt: string) {
      const now = new Date().getTime();
      const oneHourBefore = now - 60 * 60 * 1000;
      const monitors = this.monitors.map(m => m._id).join(':');
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

      let mtInfo = this.mtMap.get(mt) as MonitorType;
      ret.title!.text = `${mtInfo.desp}趨勢圖`;

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
    getMapCenter(data: AisData): any {
      let lats = data.ships.map(ship => ship.position!.lat);
      let lngs = data.ships.map(ship => ship.position!.lng);

      let latMin = Math.min.apply(Math, lats);
      let latMax = Math.max.apply(Math, lats);
      let lngMin = Math.min.apply(Math, lngs);
      let lngMax = Math.max.apply(Math, lngs);
      let lat = (latMin + latMax) / 2;
      let lng = (lngMin + lngMax) / 2;
      return { lat, lng };
    },
    getMtName(mt: string): string {
      let mtInfo = this.mtMap.get(mt) as MonitorType;
      if (mtInfo !== undefined) return mtInfo.desp;
      else return '';
    },
    async getLatestAisData() {
      try {
        let ret = await axios.get('/LatestAisData');
        if (ret.status === 200) {
          this.aisData = ret.data.aisData;
          for (let data of this.aisData) {
            for (let ship of data.ships) {
              let lat = Number.parseFloat(ship.LAT);
              let lng = Number.parseFloat(ship.LON);
              ship.position = { lat, lng };
            }
          }
          console.info(this.aisData);
        }
      } catch (err) {
        console.error(err);
      }
    },
    getAisTitle(data: AisData): string {
      return `${this.mMap.get(data.monitor).desc} AIS 即時圖`;
    },
  },
});
</script>
