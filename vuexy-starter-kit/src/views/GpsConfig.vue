<template>
  <div>
    <b-form-group
      label="啟動超出範圍事件"
      label-for="enableAlert"
      label-cols-md="3"
    >
      <b-form-checkbox
        v-model.number="paramObj.enableAlert"
        @change="onChange"
      ></b-form-checkbox>
    </b-form-group>
    <b-form-group label="經度" label-for="lon" label-cols-md="3">
      <b-form-input
        v-model.number="paramObj.lon"
        @change="onChange"
      ></b-form-input>
    </b-form-group>
    <b-form-group label="緯度" label-for="lat" label-cols-md="3">
      <b-form-input
        v-model.number="paramObj.lat"
        @change="onChange"
      ></b-form-input>
    </b-form-group>
    <b-form-group label="距離" label-for="radius" label-cols-md="3">
      <b-form-input
        v-model.number="paramObj.radius"
        @change="onChange"
      ></b-form-input>
    </b-form-group>
  </div>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script>
import Vue from 'vue';
import { mapState, mapGetters } from 'vuex';

export default Vue.extend({
  props: {
    paramStr: {
      type: String,
      default: ``,
    },
  },
  data() {
    let paramObj = { };

    if (this.paramStr !== '{}') paramObj = JSON.parse(this.paramStr);

    return {
      paramObj,
    };
  },
  computed: {},
  methods: {
    justify() {},
    onChange(evt) {
      this.justify();
      this.$emit('param-changed', JSON.stringify(this.paramObj));
    },
  },
});
</script>
