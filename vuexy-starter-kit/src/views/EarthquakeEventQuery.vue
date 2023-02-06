<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="12">
            <b-form-group label="年度" label-for="year" label-cols-md="3">
              <v-select
                id="year"
                v-model="year"
                label="desc"
                :reduce="y => y"
                :options="years"
              />
            </b-form-group>
          </b-col>
        </b-row>
      </b-form>
    </b-card>
    <b-card v-show="years.length !== 0">
      <b-row>
        <b-col lg="7" sm="12">
          <div class="map_container">
            <GmapMap
              v-if="mapLoaded"
              ref="mapRef"
              :center="mapCenter"
              :zoom="8"
              map-type-id="terrain"
              class="map_canvas"
            >
              <GmapMarker
                v-for="(evt, index) in earthquakeEvents"
                :key="index"
                :position="getEventPos(evt)"
                :clickable="false"
                :title="getEventTitle(evt)"
                :icon="getEarthQuakeIcon(evt)"
              />
            </GmapMap>
          </div>
        </b-col>
        <b-col lg="5" sm="12">
          <b-table
            class="text-right"
            :items="earthquakeEvents"
            :fields="fields"
            select-mode="single"
            selectable
            @row-selected="onRowSelected"
          ></b-table>
        </b-col>
      </b-row>
    </b-card>
    <b-modal id="event-modal" :title="eventTitle" size="xl">
      <b-row>
        <b-col cols="8">
          <b-img :src="getReportImgUrl()" fluid-grow />
        </b-col>
        <b-col cols="4">
          <b-img :src="getBImgUrl()" fluid />
          <b-img :src="getDImgUrl()" fluid />
        </b-col>
      </b-row>
    </b-modal>
  </div>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script lang="ts">
import Vue from 'vue';
const Ripple = require('vue-ripple-directive');
import moment from 'moment';
import axios from 'axios';
import { faCircle } from '@fortawesome/free-solid-svg-icons';
interface EarthquakeData {
  dateTime: number;
  lat: number;
  lon: number;
  magnitude: number;
  depth: number;
}
interface EarthquakeYearEvents {
  year: number;
  events: Array<EarthquakeData>;
}
interface Position {
  lat: number;
  lng: number;
}

export default Vue.extend({
  data() {
    let eventMap = new Map<number, Array<EarthquakeData>>();
    let fields = [
      {
        key: 'dateTime',
        label: '地震時間',
        sortable: true,
        formatter: (v: number) => moment(v).format('y年MM月DD日 HH:mm:ss'),
      },
      {
        key: 'lon',
        label: '經度',
        sortable: true,
        formatter: (v: number) => v.toFixed(4),
      },
      {
        key: 'lat',
        label: '緯度',
        sortable: true,
        formatter: (v: number) => v.toFixed(4),
      },
      {
        key: 'magnitude',
        label: '規模',
        sortable: true,
        formatter: (v: number) => v.toFixed(1),
      },
      {
        key: 'depth',
        label: '深度',
        sortable: true,
        formatter: (v: number) => v.toFixed(1),
      },
    ];
    return {
      year: 2022,
      eventMap,
      fields,
      eventTitle: '',
      activeDateTime: 0,
      mapLoaded: false,
    };
  },
  computed: {
    years(): Array<number> {
      return Array.from(this.eventMap.keys());
    },
    earthquakeEvents(): Array<EarthquakeData> {
      return this.eventMap.get(this.year) ?? Array<EarthquakeData>();
    },
    baseUrl(): string {
      return process.env.NODE_ENV === 'development'
        ? 'http://localhost:9000/'
        : '/';
    },
    mapCenter() {
      let lat = 23.974184149523335;
      let lng = 120.98011790489949;
      return { lat, lng };
    },
  },
  async mounted() {
    this.$gmapApiPromiseLazy().then(() => {
      this.mapLoaded = true;
    });

    await this.getEarthquakeEvents();
  },
  methods: {
    async getEarthquakeEvents() {
      try {
        let res = await axios.get('/EarthquakeEvents');
        if (res.status === 200) {
          let eventMap = new Map<number, Array<EarthquakeData>>();
          for (let entry of res.data) {
            let yearEvent = entry as EarthquakeYearEvents;
            eventMap.set(yearEvent.year, yearEvent.events);
          }
          this.eventMap = eventMap;
        }
      } catch (err) {
        console.error(err);
      }
    },
    onRowSelected(events: Array<EarthquakeData>) {
      if (events.length !== 0) {
        this.eventTitle = `${moment(events[0].dateTime).format(
          'lll',
        )} - 地震時間`;
        this.activeDateTime = events[0].dateTime;
        this.$bvModal.show('event-modal');
      }
    },
    getReportImgUrl(): string {
      return `${this.baseUrl}EarthquakeReport?dateTime=${this.activeDateTime}`;
    },
    getBImgUrl(): string {
      return `${this.baseUrl}EarthquakeBImg?dateTime=${this.activeDateTime}`;
    },
    getDImgUrl(): string {
      return `${this.baseUrl}EarthquakeDImg?dateTime=${this.activeDateTime}`;
    },
    getEventPos(evt: EarthquakeData): Position {
      let lat = evt.lat;
      let lng = evt.lon;
      return { lat, lng };
    },
    getEventTitle(evt: EarthquakeData): string {
      return moment(evt.dateTime).format('y年MM月DD日 HH:mm:ss');
    },
    getEarthQuakeIcon(data: EarthquakeData): any {
      let fillColor = '#008f00';
      if (data.dateTime === this.activeDateTime) fillColor = '#ff0000';

      return {
        path: faCircle.icon[4] as string,
        fillColor,
        fillOpacity: 1,
        anchor: new google.maps.Point(
          faCircle.icon[0] / 2, // width
          faCircle.icon[1], // height
        ),
        strokeWeight: 1,
        strokeColor: '#ffffff',
        scale: 0.015,
      };
    },
  },
});
</script>
