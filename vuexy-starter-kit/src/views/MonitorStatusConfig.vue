<template>
  <div>
    <b-card title="狀態碼管理" class="text-center">
      <b-table
        small
        responsive
        :fields="columns"
        :items="monitorStatusList"
        select-mode="single"
        selectable
        selected-variant="info"
        bordered
        sticky-header
        style="max-height: 650px"
        @row-selected="onMsSelected"
      >
      </b-table>
    </b-card>
  </div>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script lang="ts">
import Vue from 'vue';
const Ripple = require('vue-ripple-directive');
import axios from 'axios';
import { MonitorType, ThresholdConfig } from './types';
import { isNumber } from 'highcharts';

interface MonitorStatus {

}

interface EditMonitorStatus extends MonitorStatus {
  dirty?: boolean;
}

export default Vue.extend({
  components: {},
  directives: {
    Ripple,
  },
  data() {
    const columns = [
      {
        key: '_id',
        label: '代碼'
      },
      {
        key: 'name',
        label: '名稱',
      },
      {
        key: 'priority',
        label: '優先級 (1最優先)',
      },
    ];
    const monitorStatusList = Array<EditMonitorStatus>();

    return {
      display: false,
      columns,
      monitorStatusList,
      selected: Array<MonitorStatus>(),
    };
  },
  async mounted() {
    await this.getMonitorStatus();
  },
  methods: {
    async getMonitorStatus() {
      try{
        const res = await axios.get('/MonitorStatus');
        if(res.status === 200){
          this.monitorStatusList = res.data;
        }
      }catch(error){
        console.log(error);
      }
    },
  },
});
</script>

<style></style>
