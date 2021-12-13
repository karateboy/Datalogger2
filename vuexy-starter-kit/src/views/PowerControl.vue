<template>
  <div>
    <h1>電源控制</h1>
    <b-card border-variant="success">
      <b-row class="p-1">
        <b-col
          v-for="equipment in equipmentList"
          :key="equipment.ctrl"
          cols="3"
        >
          <b-card
            :img-src="equipment.img"
            img-width="200"
            img-left
            :title="getEquipmentName(equipment.mt)"
          >
            <b-card-body>
              <h2>{{ getEquipmentPower(equipment.mt) }}</h2>
              <b-form-checkbox
                v-model="equipment.value"
                switch
                @change="setSignalValue(equipment.ctrl, $event)"
              />
            </b-card-body>
          </b-card>
        </b-col>
      </b-row>
    </b-card>
  </div>
</template>

<script lang="ts">
import Vue from 'vue';
import axios from 'axios';
import { MonitorType, MonitorTypeStatus } from './types';
import { mapState, mapGetters, mapActions, mapMutations } from 'vuex';
interface SignalMonitorType extends MonitorType {
  value?: boolean;
}

interface Equipment {
  img: string;
  ctrl: string;
  mt: string;
  value: boolean | undefined;
}

export default Vue.extend({
  data() {
    return {
      equipmentList: Array<Equipment>(
        {
          img: '/images/ac.png',
          ctrl: 'SWITCH1',
          mt: 'V3',
          value: undefined,
        },
        {
          img: '/images/ac.png',
          ctrl: 'SWITCH2',
          mt: 'V4',
          value: undefined,
        },
        {
          img: '/images/refregrator.png',
          ctrl: 'SWITCH3',
          mt: 'V5',
          value: undefined,
        },
        {
          img: '/images/plug.png',
          ctrl: 'SWITCH4',
          mt: 'V6',
          value: undefined,
        },
        {
          img: '/images/plug.png',
          ctrl: 'SWITCH5',
          mt: 'V7',
          value: undefined,
        },
        {
          img: '/images/plug.png',
          ctrl: 'SWITCH6',
          mt: 'V8',
          value: undefined,
        },
        {
          img: '/images/plug.png',
          ctrl: 'SWITCH7',
          mt: 'V9',
          value: undefined,
        },
        {
          img: '/images/light_bulb.png',
          ctrl: 'SWITCH8',
          mt: 'V10',
          value: undefined,
        },
      ),
      realTimeStatus: Array<MonitorTypeStatus>(),
      signalTypes: Array<SignalMonitorType>(),
      signalMap: new Map<string, SignalMonitorType>(),
      refreshTimer: 0,
    };
  },
  computed: {
    ...mapGetters('monitorTypes', ['mtMap']),
  },
  async mounted() {
    await this.fetchMonitorTypes();
    await this.getRealtimeStatus();
    await this.getSignalValues();

    let me = this;
    this.refreshTimer = setInterval(() => {
      me.getRealtimeStatus();
      me.getSignalValues();
    }, 3000);
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
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
          equipment.value = signal.value;
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
    getEquipmentName(mt: string) {
      let mtCase: MonitorType = this.mtMap.get(mt);
      if (mtCase !== undefined) {
        return mtCase.desp;
      } else return '??';
    },
    getEquipmentPower(mt: string) {
      let mtEntry = this.realTimeStatus.find(entry => entry._id === mt);
      if (mtEntry !== undefined) {
        return `${mtEntry.value} ${mtEntry.unit}`;
      } else return 'N/A';
    },
  },
});
</script>
