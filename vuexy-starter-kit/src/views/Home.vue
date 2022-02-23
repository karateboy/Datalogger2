<template>
  <b-card>
    <div class="map_container">
      <GmapMap
        ref="mapRef"
        :center="mapCenter"
        :zoom="12"
        map-type-id="roadmap"
        class="map_canvas"
        :options="{
          zoomControl: true,
          mapTypeControl: false,
          scaleControl: false,
          streetViewControl: false,
          rotateControl: false,
          fullscreenControl: true,
          disableDefaultUi: false,
        }"
      >
        <GmapMarker
          v-for="(m, index) in monitorMarkers"
          :key="`m${index}`"
          :position="m.position"
          :clickable="true"
          :title="m.title"
          :label="m.label"
          :icon="m.iconUrl"
          :z-index="getZindex(0, index)"
          @click="toggleInfoWindow(m, index)"
        />
        <gmap-info-window
          :options="infoOptions"
          :position="infoWindowPos"
          :opened="infoWinOpen"
          @closeclick="infoWinOpen = false"
        />
      </GmapMap>
    </div>
  </b-card>
</template>
<script lang="ts">
import Vue from 'vue';
import { mapActions, mapGetters, mapState } from 'vuex';
import useAppConfig from '../@core/app-config/useAppConfig';
import { Monitor } from '../store/monitors/types';
interface Location {
  lat: number;
  lng: number;
}

export default Vue.extend({
  data() {
    const infoOptions = {
      content: '',
      // optional: offset infowindow so it visually sits nicely on top of our marker
      pixelOffset: {
        width: 0,
        height: -35,
      },
    };
    let currentMidx: number = -1;
    return {
      infoWindowPos: null,
      infoWinOpen: false,
      currentMidx,
      infoOptions,
    };
  },
  computed: {
    ...mapState('monitors', ['monitors']),
    skin() {
      const { skin } = useAppConfig();
      return skin;
    },
    mapCenter(): Location {
      let lng = 0,
        lat = 0;
      this.monitors.forEach((m: Monitor) => {
        lng += m.lng;
        lat += m.lat;
      });
      lng /= this.monitors.length;
      lat /= this.monitors.length;

      return { lat, lng };
    },
    monitorMarkers(): any {
      const ret = [];

      const getIconUrl = (v: number) => {
        let url = '/static/sensor12.svg';
        return url;
      };
      let monitors = this.monitors as Array<Monitor>;
      for (const m of monitors) {
        const lng = m.lng;
        const lat = m.lat;
        const _id = m._id;
        const iconUrl = '/static/sensor1.svg';

        const label = {
          text: m.desc,
        };

        const infoText = `<strong>${m.desc}</strong>`;
        const title = m.desc;

        ret.push({
          _id,
          title,
          position: { lat, lng },
          label: Object.assign({}, label),
          infoText,
          iconUrl,
        });
      }

      return ret;
    },
  },
  async mounted() {
    await this.fetchMonitors();
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors']),
    toggleInfoWindow(marker: any, idx: number) {
      this.infoWindowPos = marker.position;
      this.infoOptions.content = marker.infoText;

      // check if its the same marker that was selected if yes toggle
      if (this.currentMidx == idx) {
        this.infoWinOpen = !this.infoWinOpen;
      }

      // if different marker set infowindow to open and reset current marker index
      else {
        this.infoWinOpen = true;
        this.currentMidx = idx;
      }
    },
    getZindex(start: number, index: number) {
      return start + index;
    },
  },
});
</script>
