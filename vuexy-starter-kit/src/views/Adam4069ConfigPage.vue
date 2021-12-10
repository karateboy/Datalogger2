<template>
  <b-table :fields="fields" :items="chs">
    <template #cell(monitorType)="row">
      <v-select
        id="monitorType"
        v-model="row.item.monitorType"
        label="desp"
        :reduce="mt => mt._id"
        :options="signalTypes"
        @input="onChange"
      />
    </template>
  </b-table>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script lang="ts">
import Vue from 'vue';
import axios from 'axios';
interface SignalConfig {
  monitorType?: string;
}
import vSelect from 'vue-select';
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
    let chs = Array<SignalConfig>();
    for (let i = 0; i < 8; i++) {
      chs.push({
        monitorType: undefined,
      });
    }

    if (this.paramStr !== '') chs = JSON.parse(this.paramStr);

    const fields = [
      {
        key: 'monitorType',
        label: '數位訊號',
      },
    ];

    return {
      chs,
      fields,
      signalTypes: Array<string>(),
    };
  },
  computed: {},
  async mounted() {
    await this.getSignalTypes();
  },
  methods: {
    async getSignalTypes() {
      const res = await axios.get('/SignalTypes');
      this.signalTypes = res.data;
    },
    justify() {},
    onChange(evt: any) {
      this.justify();
      this.$emit('param-changed', JSON.stringify(this.chs));
    },
  },
});
</script>
