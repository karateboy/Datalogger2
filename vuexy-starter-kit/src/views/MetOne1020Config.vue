<template>
  <div>
    <b-form-group label="測項" label-for="monitorType" label-cols-md="3">
      <v-select
        v-model="paramObj.slaveID"
        label="text"
        :reduce="entry => entry.id"
        :options="protocols"
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
import vSelect from 'vue-select';

export default Vue.extend({
  components: {
    vSelect,
  },
  props: {
    instType: {
      type: String,
      default: 'MetOne1020',
    },
    paramStr: {
      type: String,
      default: ``,
    },
  },
  data() {
    let paramObj = {
      model: this.instType,
      slaveID: 1,
      calibrationTime: null,
      monitorTypes: null,
      raiseTime: null,
      downTime: null,
      holdTime: null,
      calibrateZeoSeq: null,
      calibrateSpanSeq: null,
      calibratorPurgeSeq: null,
      calibratorPurgeTime: null,
      calibrateZeoDO: null,
      calibrateSpanDO: null,
      skipInternalVault: null,
    };

    if (this.paramStr !== '{}') paramObj = JSON.parse(this.paramStr);

    let protocols = [
      { id: 1, text: 'Bayern Hessen Protocol' },
      { id: 2, text: 'Command Line' },
    ];
    return {
      paramObj,
      protocols,
    };
  },
  computed: {},
  async mounted() {
    this.onChange();
  },
  methods: {
    justify() {},
    onChange() {
      this.justify();
      this.$emit('param-changed', JSON.stringify(this.paramObj));
    },
  },
});
</script>
