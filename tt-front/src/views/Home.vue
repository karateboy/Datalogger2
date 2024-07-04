<template>
  <b-row class="match-height">
    <b-col lg="12" md="12">
      <div class="map_container">
        <GmapMap
            ref="mapRef"
            :center="mapCenter"
            :zoom="13"
            map-type-id="terrain"
            class="map_canvas"
        >
          <div v-if="mapLoaded">
            <GmapMarker
                v-for="(m, index) in markers"
                :key="index"
                :position="m.position"
                :clickable="true"
                :title="m.title"
                :label="{
                  text: m.label,
                  className: 'map-label bg-white rounded border border-primary',
                  color: 'black',
                  fontSize: '12px',
                  fontWeight: '400',
                }"
                :icon="m.icon"
                @click="toggleInfoWindow(m, index)"
            />
            <gmap-info-window
                :options="infoOptions"
                :position="infoWindowPos"
                :opened="infoWinOpen"
                @closeclick="infoWinOpen = false"
            />
          </div>
        </GmapMap>
      </div>
    </b-col>
  </b-row>
</template>
<style>
.legend {
  /* min-width: 100px;*/
  background-color: white;
}

.map-label {
  margin-top: 3rem !important;
  padding: 0.2rem !important;
}

.airgreen div:before {
  background: #009865;
  background-color: rgb(0, 152, 101);
}

.airgreen {
  background-color: rgb(229, 244, 239);
}
</style>
<script>
import moment from 'moment';
import {mapActions, mapGetters, mapMutations, mapState} from 'vuex';
import axios from 'axios';

export default {
  data() {
    const range = [moment().subtract(1, 'days').valueOf(), moment().valueOf()];
    let group = undefined;
    return {
      dataTypes: [{txt: '分鐘資料', id: 'min'}],
      form: {
        monitors: [],
        dataType: 'min',
        range,
      },
      columns: [],
      rows: [],
      realTimeStatus: [],
      hasSpray: false,
      spray: false,
      spray_connected: false,
      loader: undefined,
      timer: 0,
      refreshTimer: 0,
      infoWindowPos: null,
      infoWinOpen: false,
      currentMidx: null,
      mapLoaded: false,
      infoOptions: {
        content: '',
        //optional: offset infowindow so it visually sits nicely on top of our marker
        pixelOffset: {
          width: 0,
          height: -35,
        },
      },
      group,
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapState('monitors', ['monitors']),
    ...mapState('user', ['userInfo']),
    ...mapGetters('monitorTypes', ['mtMap']),
    ...mapGetters('monitors', ['mMap']),
    mapCenter() {
      let count = 0,
          latMax = -1,
          latMin = 1000,
          lngMax = -1,
          lngMin = 1000;

      for (const stat of this.realTimeStatus) {
        const monitor = this.mMap.get(stat._id.monitor);
        let lat = monitor.location[0];
        let lng = monitor.location[1];

        if (latMin > lat) latMin = lat;
        if (latMax < lat) latMax = lat;
        if (lngMin > lng) lngMin = lng;
        if (lngMax < lng) lngMax = lng;
        count++;
      }

      if (count === 0) return {lat: 23.9534736767587, lng: 120.9682970796872};

      let lat = (latMax + latMin) / 2;
      let lng = (lngMax + lngMin) / 2;
      return {lat, lng};
    },
    zoomLevel() {
      return {};
    },
    markers() {
      const ret = [];

      const getMtUrl = mtEntries => {
        let valueStr = '';
        let valueStrList = [];
        let v = 0;
        for (let mtEntry of mtEntries) {
          let mt = mtEntry.mt;
          let mtCase = this.mtMap.get(mt);
          if (mtEntry.data.value) {
            valueStrList.push(
                `${mtCase.desp}:${mtEntry.data.value.toFixed(mtCase.prec)}`,
            );
            if (mt === 'PM25') v = mtEntry.data.value;
          }
        }
        valueStr = valueStrList.join(', ');

        let fillColor = '';
        if (v < 15.4) fillColor = `#009865`;
        else if (v < 35.4) fillColor = `#FFFB26`;
        else if (v < 54.4) fillColor = `#FF9835`;
        else if (v < 150.4) fillColor = `#CA0034`;
        else if (v < 250.4) fillColor = `#670099`;
        else if (v < 350.4) fillColor = `#7E0123`;
        else fillColor = `#7E0123`;

        let markerIcon = {
          path: google.maps.SymbolPath.CIRCLE,
          fillColor,
          fillOpacity: 1,
          scale: 7,
          strokeColor: 'white',
          strokeWeight: 0.5,
        };

        let pm25desc = '';
        return {
          pm25desc,
          markerIcon,
          valueStr,
        };
      };

      for (const stat of this.realTimeStatus) {
        let pm25 = 0;

        let mtEntries = this.userInfo.monitorTypeOfInterest.flatMap(mt => {
          const data = stat.mtDataList.find(v => v.mtName === mt);
          if (!data) return [];

          return [
            {
              mt,
              data,
            },
          ];
        });

        const {markerIcon, valueStr, pm25desc} = getMtUrl(mtEntries);

        const monitor = this.mMap.get(stat._id.monitor);
        if (!monitor) continue;
        const label = pm25desc
            ? `${monitor.desc}-${pm25desc}`
            : `${monitor.desc}`;

        let lat = monitor.location[0];
        let lng = monitor.location[1];
        ret.push({
          title: valueStr,
          position: {lat, lng},
          pm25,
          infoText: `<strong>${monitor.desc}</strong><i>${valueStr}</i>`,
          label,
          icon: markerIcon
        });
      }
      console.info('markers', ret);
      return ret;
    },
  },
  async mounted() {
    this.$gmapApiPromiseLazy().then(() => {
      this.mapLoaded = true;
    });
    /*
    this.loader = this.$loading.show({
      // Optional parameters
      container: null,
      canCancel: false,
    }); */

    await this.login();
    await this.getSignalInstrumentList();
    await this.refresh();
    this.refreshTimer = setInterval(() => {
      this.refresh();
    }, 60000);
    await this.fetchMonitors();
    await this.fetchMonitorTypes();
  },
  beforeDestroy() {
    clearInterval(this.timer);
    clearInterval(this.refreshTimer);
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors']),
    ...mapMutations(['setLogin']),
    ...mapMutations('user', ['setUserInfo']),
    toggleInfoWindow(marker, idx) {
      this.infoWindowPos = marker.position;
      this.infoOptions.content = marker.infoText;

      //check if its the same marker that was selected if yes toggle
      if (this.currentMidx == idx) {
        this.infoWinOpen = !this.infoWinOpen;
      }

      //if different marker set infowindow to open and reset current marker index
      else {
        this.infoWinOpen = true;
        this.currentMidx = idx;
      }
    },
    getPM25Explain(v) {
      if (v < 50) return '良好';
      else if (v <= 100) return '普通';
      else if (v <= 150) return '對敏感族群不健康';
      else if (v <= 200) return '對所有族群不健康';
      else return '危害';
    },
    getPM25Class(v) {
      if (v < 12) return {FPMI1: true};
      else if (v < 24) return {FPMI2: true};
      else if (v < 36) return {FPMI3: true};
      else if (v < 42) return {FPMI4: true};
      else if (v < 48) return {FPMI5: true};
      else if (v < 54) return {FPMI6: true};
      else if (v < 59) return {FPMI7: true};
      else if (v < 65) return {FPMI8: true};
      else if (v < 71) return {FPMI9: true};
      else return {FPMI10: true};
    },
    async refresh() {
      await this.fetchMonitorTypes();
      await this.fetchMonitors();
      await this.getMyGroup();
      if (this.group) {
        this.form.monitors = this.group.monitors;
        this.form.monitorTypes = this.group.monitorTypes;
      } else {
        if (this.monitorTypes.length !== 0) {
          this.form.monitorTypes = [];
          this.form.monitorTypes.push(this.monitorTypes[0]._id);
        }

        if (this.monitors.length !== 0) {
          this.form.monitors = [];
          for (const m of this.monitors) this.form.monitors.push(m._id);
        }
      }

      await this.query();
      await this.getRealtimeStatus();
      await this.getSignalValues();
    },
    async query() {
      this.rows.splice(0, this.rows.length);
      this.columns = this.getColumns();
      const monitors = this.form.monitors.join(':');
      const monitorTypes = this.userInfo.monitorTypeOfInterest.join(':');
      const url = `/LatestData/${monitors}/${monitorTypes}/${this.form.dataType}`;

      const ret = await axios.get(url);
      for (const row of ret.data.rows) {
        row.date = moment(row.date).format('MM-DD HH:mm');
      }

      this.rows = ret.data.rows;
    },
    async getRealtimeStatus() {
      try {
        const ret = await axios.get('/RealtimeStatus');
        this.realTimeStatus = ret.data;
      } catch (ex) {
        throw new Error('failed');
      }
    },
    cellDataTd(i) {
      return (_value, _key, item) => item.cellData[i].cellClassName;
    },
    getMtDesc(mt) {
      if (this.mtMap.get(mt)) {
        const mtCase = this.mtMap.get(mt);
        return `${mtCase.desp}(${mtCase.unit})`;
      } else return '';
    },
    getColumns() {
      const ret = [];
      ret.push({
        key: 'date',
        label: '時間',
      });
      let i = 0;
      for (const mt of this.userInfo.monitorTypeOfInterest) {
        for (const m of this.form.monitors) {
          const mCase = this.mMap.get(m);
          ret.push({
            key: `cellData[${i}].v`,
            label: `${mCase.desc}`,
            tdClass: this.cellDataTd(i),
          });
          i++;
        }
      }

      return ret;
    },
    async getSignalValues() {
      const res = await axios.get('/SignalValues');
      if (res.data.SPRAY === true) this.spray = true;
      else this.spray = false;
      if (res.data.SPRAY === undefined) this.spray_connected = false;
      else this.spray_connected = true;
    },
    async getSignalInstrumentList() {
      const res = await axios.get('/DoInstrumentInfoList');
      if (res.data.length === 0) this.hasSpray = false;
      else this.hasSpray = true;
    },
    async testSpray() {
      await axios.get('/TestSpray');
      let countdown = 15;
      this.timer = setInterval(() => {
        countdown--;
        this.getSignalValues();
        if (countdown === 0) {
          clearInterval(this.timer);
          this.timer = 0;
        }
      }, 1000);
    },
    getBoundsZoomLevel(bounds, mapDim) {
      var WORLD_DIM = {height: 256, width: 256};
      var ZOOM_MAX = 21;

      function latRad(lat) {
        var sin = Math.sin((lat * Math.PI) / 180);
        var radX2 = Math.log((1 + sin) / (1 - sin)) / 2;
        return Math.max(Math.min(radX2, Math.PI), -Math.PI) / 2;
      }

      function zoom(mapPx, worldPx, fraction) {
        return Math.floor(Math.log(mapPx / worldPx / fraction) / Math.LN2);
      }

      var ne = bounds.getNorthEast();
      var sw = bounds.getSouthWest();

      var latFraction = (latRad(ne.lat()) - latRad(sw.lat())) / Math.PI;

      var lngDiff = ne.lng() - sw.lng();
      var lngFraction = (lngDiff < 0 ? lngDiff + 360 : lngDiff) / 360;

      var latZoom = zoom(mapDim.height, WORLD_DIM.height, latFraction);
      var lngZoom = zoom(mapDim.width, WORLD_DIM.width, lngFraction);

      return Math.min(latZoom, lngZoom, ZOOM_MAX);
    },
    async login() {
      const cred = {user: 'taitung@epb.taitung.gov.tw', password: '12072210'};
      let res = await axios.post('/login', cred);

      const ret = res.data;
      if (ret.ok) {
        console.info('login success', ret.userData);
        const userData = ret.userData;
        const userInfo = userData.user;
        this.setLogin(true)
        this.setUserInfo(userInfo);
        if (userInfo.isAdmin) {
          this.$ability.update([
            {
              action: 'manage',
              subject: 'all',
            },
          ]);
        } else {
          this.$ability.update(userData.group.abilities);
        }
      } else {
        this.$toast({
          component: ToastificationContent,
          props: {
            title: '帳號或密碼錯誤',
            icon: 'UserIcon',
          },
        });
      }
    },
    async getMyGroup() {
      let ret = await axios.get('/Group');
      if (ret.status === 200) {
        this.group = ret.data;
      }
    },
  },
};
</script>

<style>

</style>
