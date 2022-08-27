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
        <b-col cols="6">Google Map</b-col>
        <b-col cols="6">
          <b-table
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

export default Vue.extend({
  data() {
    let eventMap = new Map<number, Array<EarthquakeData>>();
    let fields = [
      {
        key: 'dateTime',
        label: '地震時間',
        sortable: true,
        formatter: (v: number) => moment(v).format('lll'),
      },
      {
        key: 'lat',
        label: '經度',
        sortable: true,
        formatter: (v: number) => v.toFixed(4),
      },
      {
        key: 'lon',
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
  },
  async mounted() {
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
  },
});
</script>
