<template>
  <div>
    <b-form-group
      v-if="hasAddr"
      label="位址"
      label-for="address"
      label-cols-md="3"
    >
      <b-form-input
        v-model.number="paramObj.addr"
        @change="onChange"
      ></b-form-input>
    </b-form-group>
    <b-table :fields="fields" :items="paramObj.chs">
      <template #cell(enable)="row">
        <b-form-checkbox v-model="row.item.enable" @change="onChange" />
      </template>
      <template #cell(repairMode)="row">
        <b-form-checkbox v-model="row.item.repairMode" @change="onChange" />
      </template>
      <template #cell(mt)="row">
        <v-select
          id="monitorType"
          v-model="row.item.mt"
          label="desp"
          :reduce="mt => mt._id"
          :options="allMonitorTypes"
          @input="onChange"
        />
      </template>
      <template #cell(scale)="row">
        <b-form-input v-model.number="row.item.scale" @change="onChange" />
      </template>
    </b-table>
  </div>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script lang="ts">
import Vue from 'vue';
import { mapState, mapGetters } from 'vuex';
import vSelect from 'vue-select';
import axios from 'axios';
import { MonitorType } from './types';

interface E1212ChannelCfg {
  enable: boolean;
  mt: string | undefined;
  scale: number | undefined;
  repairMode: boolean | undefined;
}

interface MoxaE1212Param {
  addr: number;
  chs: Array<E1212ChannelCfg>;
}

export default Vue.extend({
  components: {
    vSelect,
  },
  props: {
    paramStr: {
      type: String,
      default: ``,
    },
    hasAddr: {
      type: Boolean,
      default: false,
    },
  },
  data() {
    let chs = Array<E1212ChannelCfg>();
    for (let i = 0; i < 8; i++) {
      chs.push({
        enable: false,
        mt: undefined,
        scale: 1,
        repairMode: false,
      });
    }
    let addr = 1;
    let paramObj: MoxaE1212Param = { addr, chs };

    if (this.paramStr !== '{}') paramObj = JSON.parse(this.paramStr);

    const fields = [
      {
        key: 'enable',
        label: '啟用',
      },
      {
        key: 'repairMode',
        label: '維修模式',
      },
      {
        key: 'mt',
        label: '測項/數位訊號',
      },
      {
        key: 'scale',
        label: '讀值係數',
      },
    ];

    let signalTypes = Array<MonitorType>();
    return {
      paramObj,
      fields,
      signalTypes,
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapGetters('monitorTypes', ['mtMap']),
    allMonitorTypes(): Array<MonitorType> {
      let ret = [];
      for (let mt of this.monitorTypes) {
        ret.push(mt);
      }
      for (let mt of this.signalTypes) {
        ret.push(mt);
      }

      return ret;
    },
  },
  async mounted() {
    await this.getSignalTypes();
  },
  methods: {
    async getSignalTypes() {
      try {
        const res = await axios.get('/SignalTypes');
        this.signalTypes = res.data;
      } catch (err) {
        throw new Error('failed to get signal types');
      }
    },
    justify() {
      for (const ch of this.paramObj.chs) {
        if (!ch.scale) ch.scale = undefined;
      }
    },
    onChange(evt: any) {
      this.justify();
      this.$emit('param-changed', JSON.stringify(this.paramObj));
    },
  },
});
</script>
