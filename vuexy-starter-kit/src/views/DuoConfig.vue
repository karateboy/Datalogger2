<template>
  <b-form @submit.prevent @change="onChange">
    <b-row>
      <b-col cols="12">
        <b-form-group label="測項" label-for="monitor" label-cols-md="3">
          <v-select
            id="monitorType"
            v-model="config.monitorTypes"
            label="configID"
            multiple
            :options="monitorTypes"
          />
          <b-button variant="gradient-primary" @click="getSupportedMonitorTypes"
            >偵測測項</b-button
          >
        </b-form-group>
      </b-col>
    </b-row>
  </b-form>
</template>
<script lang="ts">
import Vue from 'vue';
import axios from 'axios';
interface DuoMonitorTypeConfig {
  id: string;
  configID: string;
  instant: boolean;
  spectrum: boolean;
  weather: boolean;
}

interface DuoConfig {
  monitorTypes: Array<DuoMonitorTypeConfig>;
}

export default Vue.extend({
  props: {
    host: {
      type: String,
      default: '',
    },
    paramStr: {
      type: String,
      default: ``,
    },
    loading: {
      type: Boolean,
      default: false,
    },
  },
  data() {
    let config: DuoConfig = {
      monitorTypes: [],
    };

    if (this.paramStr !== '{}') config = JSON.parse(this.paramStr);

    let monitorTypes = Array<DuoMonitorTypeConfig>();

    return {
      config,
      monitorTypes,
    };
  },
  watch: {
    loading(newValue: boolean) {
      if (newValue) {
        this.getSupportedMonitorTypes();
      }
    },
  },
  methods: {
    async getSupportedMonitorTypes() {
      try {
        const res = await axios.get('/ProbeDuoMonitorTypes', {
          params: { host: this.host },
        });

        if (res.status === 200) {
          this.monitorTypes = res.data;
          console.log(this.monitorTypes);
        }
      } catch (err) {
        throw new Error(err);
      }
    },
    justify() {},
    onChange() {
      this.justify();
      this.$emit('param-changed', JSON.stringify(this.config));
    },
  },
});
</script>
