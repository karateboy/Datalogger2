<template>
  <b-table :fields="fields" :items="chs">
    <template #cell(enable)="row">
      <b-form-checkbox v-model="row.item.enable" @change="onChange" />
    </template>
    <template #cell(repairMode)="row">
      <b-form-checkbox v-model="row.item.repairMode" @change="onChange" />
    </template>
    <template #cell(monitorType)="row">
      <v-select
        v-model="row.item.monitorType"
        label="desp"
        :reduce="mt => mt._id"
        :options="accumulatedMonitorType"
        @input="onChange"
      />
    </template>
    <template #cell(multiplier)="row">
      <b-form-input v-model.number="row.item.multiplier" @change="onChange" />
    </template>
  </b-table>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script lang="ts">
import Vue from 'vue';
import { mapState, mapGetters, mapActions } from 'vuex';
import vSelect from 'vue-select';
import { isNumber } from 'highcharts';
import { MonitorType } from './types';
interface CounterConfig {
  enable: boolean;
  monitorType?: string;
  multiplier?: number;
  repairMode?: boolean;
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
  },
  data() {
    let chs = Array<CounterConfig>();
    for (let i = 0; i < 2; i++) {
      chs.push({
        enable: false,
        monitorType: undefined,
        multiplier: 1,
        repairMode: false,
      });
    }

    if (this.paramStr !== '') chs = JSON.parse(this.paramStr);

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
        key: 'monitorType',
        label: '測項',
      },
      {
        key: 'multiplier',
        label: '讀值乘數',
      },
    ];

    return {
      chs,
      fields,
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapGetters('monitorTypes', ['mtMap']),
    accumulatedMonitorType(): Array<string> {
      return this.monitorTypes.filter((mt: MonitorType) => {
        return mt.accumulated;
      });
    },
  },
  async mounted() {
    await this.fetchMonitorTypes();
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    justify() {
      for (const ch of this.chs) {
        if (!isNumber(ch.multiplier)) ch.multiplier = 1;
      }
    },
    onChange(evt: any) {
      this.justify();
      this.$emit('param-changed', JSON.stringify(this.chs));
    },
  },
});
</script>
