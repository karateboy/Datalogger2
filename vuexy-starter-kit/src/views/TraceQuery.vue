<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-form-group label="測點" label-for="monitor" label-cols-md="3">
          <v-select
            id="monitor"
            v-model="form.monitor"
            label="desc"
            :reduce="(mt) => mt._id"
            :options="monitors"
          />
        </b-form-group>
        <b-form-group label="資料種類" label-for="dataType" label-cols-md="3">
          <v-select
            id="dataType"
            v-model="form.dataType"
            label="txt"
            :reduce="(dt) => dt.id"
            :options="dataTypes"
          />
        </b-form-group>
        <b-form-group
          label="監測資料區間"
          label-for="dataRange"
          label-cols-md="3"
        >
          <date-picker
            id="dataRange"
            v-model="form.range"
            :range="true"
            type="datetime"
            format="YYYY-MM-DD HH:mm"
            value-type="timestamp"
            :show-second="false"
          />
        </b-form-group>
        <b-row>
          <b-col offset-md="3">
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              type="submit"
              variant="primary"
              class="mr-1"
              @click="query"
            >
              查詢
            </b-button>
            <b-button
              v-ripple.400="'rgba(186, 191, 199, 0.15)'"
              class="mr-1"
              type="reset"
              variant="outline-secondary"
            >
              取消
            </b-button>
          </b-col>
        </b-row>
      </b-form>
    </b-card>

    <b-card
      v-show="displayRoute"
      border-variant="primary"
      :header="shipRouteTitle"
      header-tag="h2"
    >
      <div class="map_container">
        <GmapMap
          ref="mapRef"
          :center="mapCenter"
          :zoom="13"
          map-type-id="roadmap"
          class="map_canvas"
          :options="mapOption"
        >
          <div v-if="mapLoaded">
            <GmapMarker
              v-if="mapLoaded"
              key="master"
              :position="mapCenter"
              :clickable="true"
            />
            <GmapPolyline
              stroke-color="red"
              :path="trace.trace"
            />
            <GmapMarker
              v-for="(pos, markerIdx) in trace.trace"
              :key="`marker${markerIdx}`"
              :position="pos"
              :clickable="true"
              :title="getTraceTitle(pos)"
              :icon="traceIcon"
            />
          </div>
        </GmapMap>
      </div>
    </b-card>
  </div>
</template>
<style lang="scss">
@import "@core/scss/vue/libs/vue-select.scss";
</style>
<style scoped>
.legend {
  /* min-width: 100px;*/
  background-color: sliver;
}
</style>
<script lang="ts">
import Vue from 'vue';
import vSelect from 'vue-select';
import DatePicker from 'vue2-datepicker';
import 'vue2-datepicker/index.css';
import 'vue2-datepicker/locale/zh-tw';
import { mapActions, mapGetters, mapMutations, mapState } from 'vuex';
import { Monitor } from '@/store/monitors/types';
import moment from 'moment';
import axios from 'axios';
import { faCircle, faFerry, faSailboat } from '@fortawesome/free-solid-svg-icons';
import { Position, RecordList } from './types';

const Ripple = require('vue-ripple-directive');


interface TraceResult {
  trace: Array<Position>;
}

export default Vue.extend({
  components: {
    DatePicker,
    vSelect,
  },
  directives: {
    Ripple,
  },
  data() {
    const range = [
      moment().subtract(1, 'days').startOf('day').valueOf(),
      moment().add(1, 'days').startOf('day').valueOf(),
    ];

    let mapLoaded = false;
    let trace: TraceResult = {
      trace: [],
    };
    let mapOption = {
      zoomControl: true,
      mapTypeControl: true,
      scaleControl: true,
      streetViewControl: false,
      rotateControl: true,
      fullscreenControl: true,
    };
    let dataTypes = [
      { txt: '小時資料', id: 'hour' },
      { txt: '分鐘資料', id: 'min' },
    ];

    return {
      form: {
        monitor: '',
        dataType: 'hour',
        range,
      },
      dataTypes,
      trace,
      mapOption,
      mapLoaded,
      selectedMarker: -1,
    };
  },
  computed: {
    ...mapState('monitors', ['monitors']),
    ...mapGetters('monitors', ['mMap', 'monitorOfNoEPA']),
    ...mapGetters('monitorTypes', ['mtMap', 'activatedMonitorTypes']),
    shipRouteTitle(): string {
      if (!this.form.monitor) return '';

      let mCase = this.mMap.get(this.form.monitor) as Monitor;
      let start = moment(this.form.range[0]).format('lll');
      let end = moment(this.form.range[1]).format('lll');
      return `${mCase.desc}${start}至${end}`;
    },
    displayRoute(): boolean {
      return this.mapLoaded && this.trace.trace.length > 0;
    },
    mapCenter(): Position {
      if (this.trace.trace.length === 0) {
        if (this.form.monitor !== '') {
          let monitor = this.mMap.get(this.form.monitor) as Monitor;
          return {
            lat: monitor.lat ?? 23.587100069188324,
            lng: monitor.lng ?? 121.14727819361042,
          };
        } else return { lat: 23.587100069188324, lng: 121.14727819361042 };
      }

      let headPos = this.trace.trace[0];
      return { lat: headPos.lat, lng: headPos.lng };
    },
    masterShipIcon(): any {
      if (!this.mapLoaded) return {};

      return {
        path: faFerry.icon[4] as string,
        fillColor: '#ff0000',
        fillOpacity: 1,
        anchor: new google.maps.Point(
          faFerry.icon[0] / 2, // width
          faFerry.icon[1], // height
        ),
        strokeWeight: 1,
        strokeColor: '#ffffff',
        scale: 0.05,
      };
    },
    traceIcon(): any {
      if (!this.mapLoaded) return {};

      return {
        path: faCircle.icon[4] as string,
        fillColor: "red",
        fillOpacity: 1,
        anchor: new google.maps.Point(
          faCircle.icon[0] / 2, // width
          faCircle.icon[1] / 2 // height
        ),
        strokeWeight: 1,
        strokeColor: "red",
        scale: 0.01,
      };
    },
  },
  async mounted() {
    await this.fetchMonitors();
    await this.fetchMonitorTypes();

    if (this.monitors.length !== 0) {
      this.form.monitor = this.monitors[0]._id;
    }

    this.$gmapApiPromiseLazy().then(() => {
      this.mapLoaded = true;
    });

  },
  methods: {
    ...mapActions('monitors', ['fetchMonitors']),
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapMutations(['setLoading']),
    async query() {
      const url = `/TraceQuery/${this.form.monitor}/${this.form.dataType}/${this.form.range[0]}/${this.form.range[1]}`;
      this.selectedMarker = -1;
      try {
        this.setLoading({ loading: true });
        let res = await axios.get(url);
        if (res.status === 200) {
          this.trace = res.data as TraceResult;
          if (this.trace.trace.length === 0)
            this.$bvToast.toast('無資料', {
              title: '查詢結果',
              variant: 'success',
              autoHideDelay: 2000,
            });
        }
      } catch (err) {
        console.error(`${err}`);
      } finally {
        this.setLoading({ loading: false });
      }
    },

    getRecordIcon(recordList: RecordList): object {
      if (!this.mapLoaded) return {};

      const fillColor = 'white';
      return {
        path: faCircle.icon[4] as string,
        fillColor,
        fillOpacity: 1.0,
        anchor: new google.maps.Point(
          faCircle.icon[0] / 2, // width
          faCircle.icon[1] / 2, // height
        ),
        strokeWeight: 0,
        strokeColor: '#000000',
        scale: this.form.dataType === 'hour' ? 0.06 : 0.04,
      };
    },
    getRecordPos(recordList: RecordList): any {
      let latRecord = recordList.mtDataList.find(
        mtData => mtData.mtName === 'LAT',
      );
      let lngRecord = recordList.mtDataList.find(
        mtData => mtData.mtName === 'LNG',
      );

      if (
        latRecord === undefined ||
        latRecord.value === undefined ||
        lngRecord === undefined ||
        lngRecord.value === undefined
      )
        return {};

      return {
        lat: latRecord.value,
        lng: lngRecord.value,
      };
    },
    getTraceTitle(pos: Position): string {
      if (pos === undefined) return '';
      let dt = pos.date;
      return `${moment(dt).format('lll')}`;
    },
  },
});
</script>
