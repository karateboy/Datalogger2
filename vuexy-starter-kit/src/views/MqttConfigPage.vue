<template>
  <b-form @submit.prevent @change="onChange">
    <b-row>
      <b-col cols="12">
        <validation-provider
          v-slot="{ errors }"
          name="測點代碼"
          rules="required"
        >
          <b-form-group label="測點代碼" label-for="monitor" label-cols-md="3">
            <b-form-input v-model="paramObj.monitor"></b-form-input>
            <small class="text-danger">{{ errors[0] }}</small>
          </b-form-group>
        </validation-provider>
      </b-col>
      <b-col cols="12">
        <validation-provider v-slot="{ errors }" name="Topic" rules="required">
          <b-form-group label="Topic" label-for="monitor" label-cols-md="3">
            <b-form-input v-model="paramObj.topic"></b-form-input>
            <small class="text-danger">{{ errors[0] }}</small>
          </b-form-group>
        </validation-provider>
      </b-col>
      <b-col cols="12">
        <b-form-group
          label="高值警報設備"
          label-for="instrument"
          label-cols-md="3"
        >
          <b-form-input v-model="paramObj.eventConfig.instId"></b-form-input>
        </b-form-group>
      </b-col>
      <b-col cols="12">
        <b-form-group label="點位" label-for="bit" label-cols-md="3">
          <v-select
            v-model="paramObj.eventConfig.bit"
            label="txt"
            :reduce="bit => bit.value"
            :options="bits"
          />
        </b-form-group>
      </b-col>
    </b-row>
  </b-form>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script>
import Vue from 'vue';
import { mapState, mapGetters } from 'vuex';
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
    let paramObj = {
      monitor: 'mqtt1',
      topic: '',
      eventConfig: {
        instId: '',
        bit: 0,
      },
    };

    if (this.paramStr) paramObj = JSON.parse(this.paramStr);

    return {
      paramObj,
      bits: [
        {
          txt: 'D0',
          value: 16,
        },
        {
          txt: 'D1',
          value: 17,
        },
      ],
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapGetters('monitorTypes', ['mtMap']),
  },
  methods: {
    justify() {},
    onChange(evt) {
      this.justify();
      this.$emit('param-changed', JSON.stringify(this.paramObj));
    },
  },
});
</script>
