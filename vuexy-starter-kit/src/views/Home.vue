<template>
  <b-row class="match-height">
    <b-col lg="9" md="12">
      <b-card ref="loadingContainer" title="最新監測資訊">
        <b-table striped hover :fields="columns" :items="rows" show-empty>
          <template #thead-top>
            <b-tr>
              <b-th></b-th>
              <b-th
                v-for="mt in form.monitorTypes"
                :key="mt"
                :colspan="form.monitors.length"
                class="text-center"
                style="text-transform: none"
                >{{ getMtDesc(mt) }}</b-th
              >
            </b-tr>
          </template>
        </b-table>
      </b-card>
    </b-col>
    <b-col lg="3" md="12">
      <b-card title="灑水系統">
        <b-row no-gutters>
          <b-col cols="3" class="pt-2">
            <b-img
              src="../assets/images/sprinkler.svg"
              class="rounded-0 align-middle"
            />
          </b-col>
          <b-col cols="9">
            <b-card-body>
              <b-card-text :class="{ 'text-danger': !spray }"
                >啟動:{{ sprayStatus }}</b-card-text
              >
              <b-card-text :class="{ 'text-danger': !spray_connected }"
                >連線狀態:{{ sprayConnected }}</b-card-text
              >
              <b-button
                variant="primary"
                :disabled="timer !== 0"
                @click="testSpray"
                >測試</b-button
              >
            </b-card-body>
          </b-col>
        </b-row>
      </b-card>
    </b-col>
    <b-col lg="12" md="12">
      <b-card title="監測地圖🚀">
        <div class="map_container">
          <GmapMap
            ref="mapRef"
            :center="mapCenter"
            :zoom="16"
            map-type-id="terrain"
            class="map_canvas"
          >
            <GmapMarker
              v-for="(m, index) in markers"
              :key="index"
              :position="m.position"
              :clickable="true"
              :draggable="true"
              :title="m.title"
              :icon="m.iconUrl"
              @click="toggleInfoWindow(m, index)"
            />
            <gmap-info-window
              :options="infoOptions"
              :position="infoWindowPos"
              :opened="infoWinOpen"
              @closeclick="infoWinOpen = false"
            />
          </GmapMap>

          <div id="legend" class="legend shadow border border-dark m-2">
            <b-img src="../assets/images/legend.png" width="130" />
          </div>
        </div>
      </b-card>
    </b-col>
  </b-row>
</template>
<style scoped>
.legend {
  /* min-width: 100px;*/
  background-color: white;
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
import { mapActions, mapState, mapGetters } from 'vuex';
import axios from 'axios';
export default {
  data() {
    const range = [moment().subtract(1, 'days').valueOf(), moment().valueOf()];
    return {
      dataTypes: [
        // { txt: '小時資料', id: 'hour' },
        { txt: '分鐘資料', id: 'min' },
        // { txt: '秒資料', id: 'second' },
      ],
      form: {
        monitors: [],
        monitorTypes: [],
        dataType: 'min',
        range,
      },
      columns: [],
      rows: [],
      realTimeStatus: [],
      spray: false,
      spray_connected: false,
      loader: undefined,
      timer: 0,
      refreshTimer: 0,
      infoWindowPos: null,
      infoWinOpen: false,
      currentMidx: null,

      infoOptions: {
        content: '',
        //optional: offset infowindow so it visually sits nicely on top of our marker
        pixelOffset: {
          width: 0,
          height: -35,
        },
      },
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapState('monitors', ['monitors']),
    ...mapGetters('monitorTypes', ['mtMap']),
    ...mapGetters('monitors', ['mMap']),
    sprayStatus() {
      if (!this.spray_connected) return '未知';

      if (this.spray) return '否';
      else return '是';
    },
    sprayConnected() {
      if (this.spray_connected) return '正常';
      else return '斷線';
    },
    mapCenter() {
      let count = 0,
        lat = 0,
        lng = 0;

      for (const stat of this.realTimeStatus) {
        const latEntry = stat.mtDataList.find(v => v.mtName === 'LAT');
        if (!latEntry) continue;

        lat += latEntry.value;

        const lngEntry = stat.mtDataList.find(v => v.mtName === 'LNG');
        if (!lngEntry) continue;

        lng += lngEntry.value;

        count++;
      }
      if (count === 0) return { lat, lng };

      lat /= count;
      lng /= count;
      return { lat, lng };
    },
    markers() {
      const ret = [];
      let count = 0;
      const getIconUrl = v => {
        let url = `https://chart.googleapis.com/chart?chst=d_bubble_text_small_withshadow&&chld=bb|`;

        if (v < 15.4) url += `${v}|009865|000000`;
        else if (v < 35.4) url += `${v}|FFFB26|000000`;
        else if (v < 54.4) url += `${v}|FF9835|000000`;
        else if (v < 150.4) url += `${v}|CA0034|000000`;
        else if (v < 250.4) url += `${v}|670099|000000`;
        else if (v < 350.4) url += `${v}|7E0123|000000`;
        else url += `${v}|7E0123|FFFFFF`;

        return url;
      };

      for (const stat of this.realTimeStatus) {
        let lat = 0,
          lng = 0,
          pm25 = 0;
        const latEntry = stat.mtDataList.find(v => v.mtName === 'LAT');
        if (!latEntry) continue;

        const lngEntry = stat.mtDataList.find(v => v.mtName === 'LNG');
        if (!lngEntry) continue;

        lat = latEntry.value;
        lng = lngEntry.value;

        const pm25Entry = stat.mtDataList.find(v => v.mtName === 'PM25');
        if (!pm25Entry) continue;
        pm25 = pm25Entry.value;

        const iconUrl = getIconUrl(pm25);
        console.log(stat);
        console.log(this.mMap);
        ret.push({
          title: this.mMap.get(stat.monitor).desc,
          position: { lat, lng },
          pm25,
          infoText: `<strong>${this.mMap.get(stat.monitor).desc}</strong>`,
          iconUrl,
        });
        count++;
      }

      return ret;
    },
  },
  mounted() {
    const legend = document.getElementById('legend');
    this.$refs.mapRef.$mapPromise.then(map => {
      map.controls[google.maps.ControlPosition.LEFT_CENTER].push(legend);
    });

    /*
    this.loader = this.$loading.show({
      // Optional parameters
      container: null,
      canCancel: false,
    }); */

    this.refresh();
    this.refreshTimer = setInterval(() => {
      this.refresh();
    }, 30000);
  },
  beforeDestroy() {
    clearInterval(this.timer);
    clearInterval(this.refreshTimer);
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors']),
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
    async getSignalValues() {
      const res = await axios.get('/SignalValues');
      if (res.data.SPRAY === true) this.spray = true;
      else this.spray = false;
      if (res.data.SPRAY === undefined) this.spray_connected = false;
      else this.spray_connected = true;
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
    getPM25Class(v) {
      if (v < 12) return { FPMI1: true };
      else if (v < 24) return { FPMI2: true };
      else if (v < 36) return { FPMI3: true };
      else if (v < 42) return { FPMI4: true };
      else if (v < 48) return { FPMI5: true };
      else if (v < 54) return { FPMI6: true };
      else if (v < 59) return { FPMI7: true };
      else if (v < 65) return { FPMI8: true };
      else if (v < 71) return { FPMI9: true };
      else return { FPMI10: true };
    },
    async refresh() {
      await this.fetchMonitorTypes();
      if (this.monitorTypes.length !== 0) {
        this.form.monitorTypes = [];
        this.form.monitorTypes.push(this.monitorTypes[0]._id);
      }

      await this.fetchMonitors();
      if (this.monitors.length !== 0) {
        this.form.monitors = [];
        for (const m of this.monitors) this.form.monitors.push(m._id);
      }

      this.query();
      this.getRealtimeStatus();
      this.getSignalValues();
    },
    async query() {
      this.rows.splice(0, this.rows.length);
      this.columns = this.getColumns();
      const monitors = this.form.monitors.join(':');
      const monitorTypes = this.form.monitorTypes.join(':');
      const url = `/LatestData/${monitors}/${monitorTypes}/${this.form.dataType}`;

      const ret = await axios.get(url);
      for (const row of ret.data.rows) {
        row.date = moment(row.date).format('LLL');
      }

      this.rows = ret.data.rows;
    },
    async getRealtimeStatus() {
      const ret = await axios.get('/RealtimeStatus');
      this.realTimeStatus = ret.data;
    },
    cellDataTd(i) {
      return (_value, _key, item) => item.cellData[i].cellClassName;
    },
    getMtDesc(mt) {
      const mtCase = this.mtMap.get(mt);
      return `${mtCase.desp}(${mtCase.unit})`;
    },
    getColumns() {
      const ret = [];
      ret.push({
        key: 'date',
        label: '時間',
      });
      let i = 0;
      for (const mt of this.form.monitorTypes) {
        const mtCase = this.mtMap.get(mt);
        for (const m of this.form.monitors) {
          // emtpyCell  ${mtCase.desp}(${mtCase.unit})
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
  },
};
</script>

<style></style>
