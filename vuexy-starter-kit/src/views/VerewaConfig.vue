<template>
  <div>
    <b-form-group label="測項" label-for="monitorType" label-cols-md="3">
      <v-select
        id="monitorType"
        v-model="paramObj.monitorType"
        label="desp"
        :reduce="mt => mt._id"
        :options="verewaMonitorTypes"
        @input="onChange"
      />
    </b-form-group>
  </div>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script lang="ts">
import Vue from 'vue';
import { mapState, mapGetters } from 'vuex';
import vSelect from 'vue-select';
import { MonitorType } from './types';

interface VereWaConfig {
  monitorType: string;
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
    let monitorType = 'PM25';
    let paramObj: VereWaConfig = { monitorType };

    if (this.paramStr !== '{}') paramObj = JSON.parse(this.paramStr);

    return {
      paramObj,
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapGetters('monitorTypes', ['mtMap']),
    verewaMonitorTypes(): Array<MonitorType> {
      let ret = [];

      for (let mt of this.monitorTypes) {
        let mtCase = mt as MonitorType;
        if (mtCase._id === 'PM25' || mtCase._id === 'PM10') ret.push(mt);
      }

      return ret;
    },
  },
  async mounted() {
    this.onChange(null);
  },
  methods: {
    justify() {},
    onChange(evt: any) {
      this.justify();
      this.$emit('param-changed', JSON.stringify(this.paramObj));
    },
  },
});
</script>
