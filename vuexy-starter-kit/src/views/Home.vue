<!-- eslint-disable prettier/prettier -->
<template>
  <b-row class="match-height">
    <b-col lg="6" md="12">
      <b-card
          class="text-center"
          no-body>
        <b-card-header :header-class="Object.assign(headerBG, {'pt-1':true, 'pl-1':true, 'pb-0':true})">
          <h4><strong>即時空氣品質監測</strong></h4>
          <b-form-group v-slot="{ ariaDescribedby }">
            <b-form-radio-group
                id="radio-group-1"
                v-model="mapMonitorType"
                :options="myMonitorTypesOptions"
                :aria-describedby="ariaDescribedby"
                name="radio-options"
            ></b-form-radio-group>
          </b-form-group>
        </b-card-header>
        <div class="map_container">
          <div id="mapLegend" class="mb-2 rounded">
            <b-img v-if="mapMonitorType==='PM25'" src="../assets/images/pm25_legend.png" width="400" fluid
                   class="float-right"/>
            <b-img v-else-if="mapMonitorType==='PM10'" src="../assets/images/pm10_legend.png" width="400" fluid
                   class="float-right"/>
          </div>
          <GmapMap
              ref="mapRef"
              :center="mapCenter"
              :zoom="18"
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
      </b-card>
    </b-col>
    <b-col>
      <b-row>
        <b-col>
          <b-card no-body>
              <b-row no-gutters>
                <b-col class="pt-1 pl-1 pb-0" :class="headerBG"><h4><strong>即時空氣品質監測</strong></h4></b-col>
                <b-col class="pt-1 pl-1 pb-0" :class="headerBG"><h4><strong>灑水系統狀態</strong></h4></b-col>
              </b-row>
              <b-row no-gutters>
                <b-col class="border-right">
                  <b-table-simple>
                    <b-tr>
                      <b-th colspan="3">即時最高濃度</b-th>
                    </b-tr>
                    <b-tr v-for="mtSummary in realtimeSummary.mtSummaries" :key="mtSummary.mt">
                      <b-th>{{getMtName(mtSummary.mt)}}最高值</b-th>
                      <b-th>{{ mtSummary.max }}&nbsp; {{getMtUnitName(mtSummary.mt)}}</b-th>
                      <b-td>
                      <span v-if="isValueNormal('PM10', realtimeSummary.maxPM10)">
                      <b-img src="../assets/images/normal.png"
                             width="25px"
                             class="mb-50"
                             fluid/>
                        <br/>
                        <strong class="mt-1">正常</strong>
                      </span>
                        <span v-else>
                        <b-img src="../assets/images/over_std.png"
                               width="25px"
                               class="mb-50"
                               fluid/>
                          <br/>
                        <br/>
                        <strong>超標</strong>
                      </span>
                      </b-td>
                    </b-tr>
                    <b-tr>
                      <b-th>感測器連接狀況</b-th>
                      <b-th colspan="2">正常:{{ realtimeSummary.connected }} 斷線:{{
                          realtimeSummary.disconnected
                        }}
                      </b-th>
                    </b-tr>
                  </b-table-simple>
                </b-col>
                <b-col>
                  <b-table-simple borderless>
                    <b-tr>
                      <b-td>
                        <b-button
                            class="m-2"
                            size="sm"
                            variant="primary"
                            :disabled="timer !== 0"
                            @click="testSpray"
                        >啟動
                        </b-button>
                      </b-td>
                      <b-td rowspan="2" class="align-middle">
                        <b-img fluid
                               width="75"
                               src="../assets/images/sprinkler.svg"
                               class="rounded-0 align-middle"
                        />
                      </b-td>
                      <b-td class="align-middle">
                        <h4 v-if="timer" class="text-info">剩餘時間:{{ countdown }}</h4>
                        <h4 :class="{ 'text-danger': !spray}"><strong>啟動:<span style="color:lime;">{{ sprayStatus }}</span></strong></h4>
                      </b-td>
                    </b-tr>
                    <b-tr>
                      <b-td>
                        <b-button
                            class="m-2"
                            size="sm"
                            variant="danger"
                            :disabled="timer === 0"
                            @click="testSpray">停止</b-button>
                      </b-td>
                      <b-td class="align-middle">
                        <h4 :class="{ 'text-danger': !spray_connected}"><strong>連線:<span style="color:lime;">{{
                            sprayConnected
                          }}</span></strong>
                        </h4>
                      </b-td>
                    </b-tr>
                  </b-table-simple>
                </b-col>
              </b-row>
          </b-card>
        </b-col>
      </b-row>
      <b-row>
        <b-col
            v-for="mt in userInfo.monitorTypeOfInterest"
            :key="mt"
        >
          <b-card no-body>
            <b-card-header :header-class="Object.assign(headerBG, {'pt-1':true, 'pl-1':true, 'pb-0':true, 'text-center':true})">
              <h4 class="flex-fill"><strong>{{ getMtName(mt) }}趨勢圖</strong></h4>
            </b-card-header>
            <div :id="`history_${mt}`"></div>
          </b-card>
        </b-col>
      </b-row>
    </b-col>
  </b-row>
</template>
<style>
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

.map-label {
  margin-top: 3rem !important;
  padding: 0.2rem !important;
}

.header-bg-color {
  background-color: #c3efd7 !important;
}

.header-bg-color-dark {
  background-color: darkolivegreen !important;
}

.sensorFilter {
  background-color: white;
}

.title {
  font-size: 1.5rem;
  font-weight: bolder;
}
</style>
<script>
import moment from 'moment';
import { mapActions, mapGetters, mapState } from 'vuex';
import axios from 'axios';
import highcharts from 'highcharts';
import useAppConfig from '@core/app-config/useAppConfig';
import { computed } from '@vue/composition-api';

export default {
  data() {
    const range = [moment().subtract(1, 'days').valueOf(), moment().valueOf()];
    let group = undefined;
    let realtimeSummary = {
      mtSummaries: [],
      connected: 0,
      disconnected: 0,
    };
    return {
      dataTypes: [{ txt: '分鐘資料', id: 'min' }],
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
      countdown: 0,
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
      mapMonitorType: 'PM25',
      realtimeSummary,
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapState('monitors', ['monitors']),
    ...mapState('user', ['userInfo']),
    ...mapGetters('monitorTypes', ['mtMap']),
    ...mapGetters('monitors', ['mMap']),
    isDark() {
      const { skin } = useAppConfig();
      return skin.value === 'dark';
    },
    headerBG() {
      return {
        'header-bg-color': !this.isDark,
        'header-bg-color-dark': this.isDark,
      };
    },
    myMonitorTypes() {
      const defaultMonitorTypes = ['PM10', 'PM25'];
      if (this.monitorTypes === undefined) return defaultMonitorTypes;
      return defaultMonitorTypes.filter(mt => this.mtMap.get(mt) !== undefined);
    },
    myMonitorTypesOptions() {
      return this.myMonitorTypes.map(mt => {
        return { value: mt, text: this.mtMap.get(mt).desp };
      });
    },
    sprayStatus() {
      if (!this.hasSpray) return '未安裝';
      if (!this.spray_connected) return '未知';
      if (this.spray) return '否';
      else return '是';
    },
    sprayConnected() {
      if (!this.hasSpray) return '未安裝';
      if (this.spray_connected) return '正常';
      else return '斷線';
    },
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

      if (count === 0) return { lat: 23.9534736767587, lng: 120.9682970796872 };

      let lat = (latMax + latMin) / 2;
      let lng = (lngMax + lngMin) / 2;
      return { lat, lng };
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
            if (mt === this.mapMonitorType) v = mtEntry.data.value;
          }
        }
        valueStr = valueStrList.join(', ');

        let fillColor;
        if (this.mapMonitorType === 'PM25') {
          if (v < 15) fillColor = `#009865`;
          else if (v < 35) fillColor = `#FFFB26`;
          else if (v < 54) fillColor = `#FF9835`;
          else if (v < 150) fillColor = `#CA0034`;
          else if (v < 250) fillColor = `#670099`;
          else if (v < 350) fillColor = `#7E0123`;
          else fillColor = `#7E0123`;
        } else {
          // PM10
          if (v < 50) fillColor = `#009865`;
          else if (v < 100) fillColor = `#FFFB26`;
          else if (v < 254) fillColor = `#FF9835`;
          else if (v < 354) fillColor = `#CA0034`;
          else if (v < 424) fillColor = `#670099`;
          else if (v < 504) fillColor = `#7E0123`;
          else fillColor = `#7E0123`;
        }

        let markerIcon = {
          path: google.maps.SymbolPath.CIRCLE,
          fillColor,
          fillOpacity: 1,
          scale: 12,
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

        const { markerIcon, valueStr, pm25desc } = getMtUrl(mtEntries);

        const monitor = this.mMap.get(stat._id.monitor);
        if (!monitor) continue;
        let label = pm25desc
          ? `${monitor.desc}-${pm25desc}`
          : `${monitor.desc}`;

        let lat = monitor.location[0];
        let lng = monitor.location[1];
        ret.push({
          title: valueStr,
          position: { lat, lng },
          pm25,
          infoText: `<strong>${monitor.desc}</strong>`,
          label,
          icon: markerIcon,
        });
      }

      // auto fit the map
      /*
      const ref = this.$refs.mapRef;
      if (ref) {
        ref.$mapPromise.then(map => {
          let bounds = new google.maps.LatLngBounds();
          for (let marker of ret) {
            bounds.extend(marker.position);
          }
          let mapDim = {
            height: map.getDiv().clientHeight,
            width: map.getDiv().clientWidth,
          };
          map.fitBounds(bounds);
          map.setZoom(this.getBoundsZoomLevel(bounds, mapDim));
        });
      }
       */
      return ret;
    },
  },
  async mounted() {
    this.$gmapApiPromiseLazy().then(() => {
      this.mapLoaded = true;
      const mapLegend = document.getElementById('mapLegend');
      if (mapLegend !== null) {
        const ref = this.$refs.mapRef;
        ref.$mapPromise.then(map => {
          map.controls[google.maps.ControlPosition.BOTTOM_LEFT].push(mapLegend);
        });
      }
    });

    await this.getGroupDoInstrumentList();
    this.refresh();
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
      for (const mt of this.userInfo.monitorTypeOfInterest) {
        await this.drawTrendChart(mt);
      }
      await this.getRealtimeSummary();
      await this.getRealtimeStatus();
      await this.getSignalValues();
    },
    async getGroupDoInstrumentList() {
      const res = await axios.get('/MyGroupDoInstrument');
      if (res.data.length === 0) this.hasSpray = false;
      else this.hasSpray = true;
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
    async drawTrendChart(mt) {
      let now = new Date().getTime();

      const oneHourBefore = now - 60 * 60 * 1000;
      const myMonitors = this.monitors.map(m => m._id).join(':');
      //const url = `/HistoryTrend/${myMonitors}/${mt}/all/Min/normal/${oneHourBefore}/${now}`;
      const url = `/HistoryTrend/${myMonitors}/${mt}/Min/normal/${oneHourBefore}/${now}`;
      const res = await axios.get(url);
      const ret = res.data;

      ret.chart = {
        type: 'spline',
        zoomType: 'x',
        panning: {
          enabled: true,
        },
        panKey: 'shift',
        alignTicks: false,
      };

      const pointFormatter = function pointFormatter() {
        const d = new Date(this.x);
        return `${d.toLocaleString()}:${Math.round(this.y)}度`;
      };

      let mtInfo = this.mtMap.get(mt);
      ret.title.text = '';
      ret.tooltip = { valueDecimals: 2 };
      ret.legend = { enabled: true };
      ret.credits = {
        enabled: false,
        href: 'http://www.wecc.com.tw/',
      };

      ret.tooltip = { valueDecimals: 2 };
      ret.legend = { enabled: true };
      ret.credits = {
        enabled: false,
        href: 'http://www.wecc.com.tw/',
      };
      ret.xAxis.type = 'datetime';
      ret.xAxis.dateTimeLabelFormats = {
        day: '%b%e日',
        week: '%b%e日',
        month: '%y年%b',
      };

      ret.plotOptions = {
        scatter: {
          tooltip: {
            pointFormatter,
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
    async getRealtimeStatus() {
      try {
        const ret = await axios.get('/RealtimeStatus');
        this.realTimeStatus = ret.data;
      } catch (ex) {
        throw new Error('failed');
      }
    },
    async getRealtimeSummary() {
      try {
        const res = await axios.get('/RealtimeSummary');
        this.realtimeSummary = res.data;
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
      this.spray = res.data.SPRAY === true;
      this.spray_connected = res.data.SPRAY !== undefined;
    },
    async testSpray() {
      await axios.get('/TestSpray');
      this.countdown = 5 * 6;
      this.timer = setInterval(() => {
        this.countdown--;
        this.getSignalValues();
        if (this.countdown === 0) {
          clearInterval(this.timer);
          this.timer = 0;
        }
      }, 1000);
    },
    getBoundsZoomLevel(bounds, mapDim) {
      var WORLD_DIM = { height: 256, width: 256 };
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
    async getMyGroup() {
      let ret = await axios.get('/Group');
      if (ret.status === 200) {
        this.group = ret.data;
      }
    },
    isValueNormal(mt, value) {
      if (this.mtMap === undefined) return true;

      let mtCase = this.mtMap.get(mt);
      if (mtCase === undefined) return true;

      let std = mtCase.std_law;
      if (value === undefined || std === undefined || value <= std) return true;

      console.info(`mt=${mt} value=${value}, std=${std}`);
      return false;
    },
    getMtName(mt) {
      const mtCase = this.mtMap.get(mt);
      if (mtCase === undefined) return '';
      return this.mtMap.get(mt).desp;
    },
    getMtUnitName(mt) {
      const mtCase = this.mtMap.get(mt);
      if (mtCase === undefined) return '';
      return this.mtMap.get(mt).unit;
    },
  },
};
</script>
