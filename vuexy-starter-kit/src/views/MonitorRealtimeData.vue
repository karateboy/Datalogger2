<template>
  <b-card title="測站即時資訊" class="text-center">
    <b-table
      striped
      hover
      :fields="fields"
      :items="recordLists"
      responsive
      small
    >
      <template #cell(_id)="data">
        <p>
          <strong>{{ getMonitorName(data.item._id) }}</strong>
        </p>
        <i>{{ getUpdateTime(data.item._id) }}</i>
      </template>
    </b-table>
  </b-card>
</template>
<script lang="ts">
import axios from 'axios';
import { MonitorType, MtRecord, RecordList, RecordListID } from './types';
import { mapGetters, mapActions, mapState } from 'vuex';
import moment from 'moment';
import {
  BvTableFieldArray,
  BvTableField,
} from 'bootstrap-vue/src/components/table/index';
interface DisplayRecordList extends RecordList {
  recordMap?: Map<string, MtRecord>;
}

interface LatestMonitorData {
  monitorTypes: Array<string>;
  monitorData: Array<RecordList>;
}

import Vue from 'vue';
export default Vue.extend({
  data() {
    let recordLists = Array<DisplayRecordList>();
    let monitorTypes = Array<string>();
    let timer = 0;
    return {
      monitorTypes,
      recordLists,
      timer,
    };
  },
  computed: {
    ...mapState('monitors', ['monitors', 'activeID']),
    ...mapGetters('monitorTypes', ['mtMap']),
    ...mapGetters('monitors', ['mMap']),
    fields(): BvTableFieldArray {
      let ret = Array<{ key: string } & BvTableField>();
      ret.push({
        key: '_id',
        label: '測站',
        isRowHeader: true,
        sortable: true,
      });
      for (let mt of this.monitorTypes) {
        let mtCase = this.mtMap.get(mt) as MonitorType;
        ret.push({
          key: mt,
          label: `${mtCase.desp}\n(${mtCase.unit})`,
          formatter: (_, mt: string, item: DisplayRecordList) => {
            let mtData = item.recordMap?.get(mt);
            if (!mtData) return '-';

            if (mtData.value === undefined) return '-';

            return `${mtData.value.toFixed(mtCase.prec)}`;
          },
          thClass: ['text-wrap'],
          thStyle: 'text-transform: none',
          sortable: true,
          tdClass: (value: any, key: string, item: DisplayRecordList) => {
            let mtCase = this.mtMap.get(mt) as MonitorType;
            let mtData = item.recordMap?.get(mt);
            if (
              mtCase.std_law &&
              mtData &&
              mtData.value &&
              mtCase.std_law <= mtData.value
            )
              return 'bg-danger';
            else return '';
          },
          tdAttr: (value: any, key: string, item: DisplayRecordList) => {
            let mtCase = this.mtMap.get(mt) as MonitorType;
            let mtData = item.recordMap?.get(mt);
            if (
              mtCase.std_law &&
              mtData &&
              mtData.value &&
              mtCase.std_law <= mtData.value
            )
              return {
                'v-b-tooltip.hover': true,
                title: '超過法規值',
              };
            else return {};
          },
        });
      }
      return ret;
    },
  },
  async mounted() {
    await this.fetchMonitorTypes();
    await this.fetchMonitors();
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
      let data = ret.data as LatestMonitorData;
      this.monitorTypes = data.monitorTypes;
      this.recordLists = data.monitorData;
      for (let recordList of this.recordLists) {
        recordList.recordMap = new Map<string, MtRecord>();
        for (let mtData of recordList.mtDataList) {
          recordList.recordMap.set(mtData.mtName, mtData);
        }
      }
    },
    getMonitorName(_id: RecordListID): string {
      return `${this.mMap.get(_id.monitor).desc}`;
    },
    getUpdateTime(_id: RecordListID): string {
      return `${moment(_id.time).fromNow()}`;
    },
  },
});
</script>
