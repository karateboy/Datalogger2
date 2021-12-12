<template>
  <b-card
    header="電源控制"
    header-class="h4 display text-center"
    border-variant="primary"
    header-bg-variant="white"
  >
    <b-row class="p-1">
      <b-col v-for="equipment in equipmentList" :key="equipment.ctrl" cols="3">
        <b-table-simple borderless>
          <b-tbody>
            <b-tr>
              <b-td rowspan="4"><b-img :src="equipment.img" fluid-grow /></b-td>
            </b-tr>
            <b-tr
              ><b-td>{{ getEquipmentName(equipment.ctrl) }}</b-td></b-tr
            >
            <b-tr
              ><b-td>{{ getEquipmentPower(equipment.mt) }}</b-td></b-tr
            >
            <b-tr
              ><b-td
                ><b-form-checkbox
                  v-model="equipment.on"
                  switch
                  @change="setSignalValue(equipment.ctrl, $event)"
              /></b-td>
            </b-tr>
          </b-tbody>
        </b-table-simple>
      </b-col>
    </b-row>
  </b-card>
</template>

<script lang="ts">
import Vue from 'vue';
import axios from 'axios';
import { MonitorType, MonitorTypeStatus } from './types';
interface SignalMonitorType extends MonitorType {
  value?: boolean;
}

interface Equipment {
  img: string;
  ctrl: string;
  mt: string;
  on: boolean | undefined;
}

export default Vue.extend({
  data() {
    return {
      equipmentList: Array<Equipment>(
        {
          img: '/images/ac.png',
          ctrl: 'SWITCH1',
          mt: 'V1',
          on: true,
        },
        {
          img: '/images/ac.png',
          ctrl: 'SWITCH2',
          mt: 'V2',
          on: true,
        },
        {
          img: '/images/refregrator.png',
          ctrl: 'SWITCH3',
          mt: 'V3',
          on: true,
        },
        {
          img: '/images/plug.png',
          ctrl: 'SWITCH4',
          mt: 'V4',
          on: true,
        },
        {
          img: '/images/plug.png',
          ctrl: 'SWITCH5',
          mt: 'V5',
          on: true,
        },
        {
          img: '/images/plug.png',
          ctrl: 'SWITCH6',
          mt: 'V6',
          on: true,
        },
        {
          img: '/images/plug.png',
          ctrl: 'SWITCH7',
          mt: 'V7',
          on: true,
        },
      ),
      realTimeStatus: Array<MonitorTypeStatus>(),
      signalTypes: Array<SignalMonitorType>(),
      signalMap: new Map<string, SignalMonitorType>(),
      refreshTimer: 0,
    };
  },
  async mounted() {
    await this.getRealtimeStatus();
    await this.getSignalValues();

    let me = this;
    this.refreshTimer = setInterval(() => {
      me.getRealtimeStatus();
      me.getSignalValues();
    }, 3000);
  },
  methods: {
    async getRealtimeStatus(): Promise<void> {
      const ret = await axios.get('/MonitorTypeStatusList');
      this.realTimeStatus = ret.data;
    },
    async getSignalValues() {
      try {
        const res = await axios.get('/SignalValues');
        this.signalTypes = res.data;
        this.signalMap.clear();
        for (let signal of this.signalTypes) {
          this.signalMap.set(signal._id, signal);
        }
      } catch (err) {
        throw new Error('failed to get signal types');
      }
    },
    updateEquipment() {
      for (let equipment of this.equipmentList) {
        let signal = this.signalMap.get(equipment.ctrl);
        if (signal !== undefined) {
          equipment.on = signal.value;
        }
      }
    },
    async setSignalValue(ctrl: string, bit: boolean) {
      try {
        const resp = await axios.get(`/SetSignal/${ctrl}/${bit}`);
      } catch (err) {
        throw new Error('failed to toggle mt');
      }
    },
    getEquipmentName(ctrl: string) {
      let signal = this.signalMap.get(ctrl);
      if (signal !== undefined) {
        return signal.desp;
      } else return '未知的設備';
    },
    getEquipmentPower(mt: string) {
      let mtEntry = this.realTimeStatus.find(entry => entry._id === mt);
      if (mtEntry !== undefined) {
        return `${mtEntry.value}(${mtEntry.unit})`;
      } else return 'N/A';
    },
  },
});
</script>
