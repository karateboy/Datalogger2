<template>
  <b-form @submit.prevent @change="onChange">
    <b-row>
      <b-col cols="12">
        <validation-provider v-slot="{ errors }" name="Topic" rules="required">
          <b-form-group label="位址" label-for="address" label-cols-md="3">
            <b-form-input v-model="paramObj.address"></b-form-input>
            <small class="text-danger">{{ errors[0] }}</small>
          </b-form-group>
        </validation-provider>
        <h2>特別注意事項, 請將儀器通訊協定設定如下!</h2>
        <h3>Protocol: Monitor Labs</h3>
        <h3>Error Checking: None</h3>
        <h3>Command Start: @</h3>
        <h3>Response Start: None</h3>
        <h3>Acknokowledge: ACK</h3>
        <h3>Nagative Acknokowledge: NACK</h3>
        <h3>Command End: CR</h3>
        <h3>Field Seperator: comma</h3>
      </b-col>
    </b-row>
  </b-form>
</template>
<script>
import Vue from 'vue';

export default Vue.extend({
  props: {
    paramStr: {
      type: String,
      default: ``,
    },
  },
  data() {
    let paramObj = {
      address: '',
    };

    if (this.paramStr !== '{}') paramObj = JSON.parse(this.paramStr);

    return {
      paramObj,
    };
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
