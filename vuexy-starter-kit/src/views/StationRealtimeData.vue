<template>
  <b-card title="即時資訊">
    <b-table striped hover :fields="fields" :items="items">
      <template #cell(index)="data">
        {{ data.index + 1 }}
      </template>
    </b-table>
  </b-card>
</template>
<script lang="ts">
import axios from 'axios';
import { RecordList, RecordListID } from './types';
import { mapGetters, mapActions, mapState } from 'vuex';
import moment from 'moment';

import Vue from 'vue';
export default Vue.extend({
  data() {
    let items = Array<RecordList>();
    let timer = 0;
    return {
      items,
      timer,
    };
  },
  computed: {
    ...mapState('monitors', ['monitors', 'activeID']),
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapGetters('monitorTypes', ['mtMap']),
    ...mapGetters('monitors', ['mMap']),
    fields(): Array<any> {
      return [
        {
          key: '_id.',
          label: '測站',
          formatter: (r: RecordListID) =>
            `${this.mMap(r.monitor).desc} ${moment(r.time).fromNow()}`,
        },
        {
          key: 'desp',
          label: '測項',
          sortable: true,
        },
        {
          key: 'value',
          label: '測值',
          sortable: true,
        },
        {
          key: 'unit',
          label: '單位',
          sortable: true,
        },
        {
          key: 'instrument',
          label: '設備',
          sortable: true,
        },
        {
          key: 'status',
          label: '狀態',
          sortable: true,
        },
      ];
    },
  },
  mounted() {
    this.getMonitorRealtimeData();
    let me = this;
    this.timer = setInterval(me.getMonitorRealtimeData, 60 * 1000);
  },
  beforeDestroy() {
    clearInterval(this.timer);
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors']),
    async getMonitorRealtimeData() {
      const ret = await axios.get('/LatestMonitorData');
      this.items.splice(0, this.items.length);
      this.items = ret.data;
    },
  },
});
</script>
