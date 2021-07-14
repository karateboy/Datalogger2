<template>
  <b-form @submit.prevent @change="onChange">
    <b-row>
      <b-col cols="12">
        <validation-provider v-slot="{ errors }" name="Topic" rules="required">
          <b-form-group label="Topic" label-for="topic" label-cols-md="3">
            <b-form-input v-model="paramObj.topic"></b-form-input>
            <small class="text-danger">{{ errors[0] }}</small>
          </b-form-group>
        </validation-provider>
      </b-col>
    </b-row>
  </b-form>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script lang="ts">
import Vue, { PropType } from 'vue';
import { MqttConfig2 } from './types';
import axios from 'axios';

export default Vue.extend({
  props: {
    paramStr: {
      type: String as PropType<string>,
      default: ``,
    },
  },
  data() {
    let paramObj: MqttConfig2 = {
      topic: '',
    };

    if (this.paramStr) paramObj = JSON.parse(this.paramStr);

    return {
      paramObj,
    };
  },
  computed: {},
  mounted() {},
  methods: {
    onChange() {
      this.$emit('param-changed', JSON.stringify(this.paramObj));
    },
  },
});
</script>
