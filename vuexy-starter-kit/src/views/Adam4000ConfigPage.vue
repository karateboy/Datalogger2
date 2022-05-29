<template>
  <div>
    <b-button variant="info" @click="addModule">新增模組</b-button>
    <b-tabs>
      <b-tab
        v-for="(moduleConfig, i) in config"
        :key="i"
        :title="`模組${i + 1}`"
      >
        <b-form @submit.prevent>
          <b-form-group label="位址" label-for="address" label-cols-md="3">
            <b-form-input v-model="moduleConfig.address" />
          </b-form-group>
          <b-form-group label="模組" label-for="module" label-cols-md="3">
            <b-form-radio-group
              v-model="moduleConfig.module"
              :options="options"
              name="radio-options"
            ></b-form-radio-group>
          </b-form-group>
          <adam-4017-config-page
            v-if="moduleConfig.module === '4017'"
            :param-str="moduleConfig.param"
            @param-changed="moduleParamChange(moduleConfig, $event)"
          />
          <adam-4069-config-page
            v-if="moduleConfig.module === '4069'"
            :param-str="moduleConfig.param"
            @param-changed="moduleParamChange(moduleConfig, $event)"
          />
          <adam-4080-config-page
            v-if="moduleConfig.module === '4080'"
            :param-str="moduleConfig.param"
            @param-changed="moduleParamChange(moduleConfig, $event)"
          />
        </b-form>
      </b-tab>
    </b-tabs>
  </div>
</template>
<script lang="ts">
import Adam4017ConfigPage from './Adam4017ConfigPage.vue';
import Adam4069ConfigPage from './Adam4069ConfigPage.vue';
import Vue from 'vue';
import Adam4080ConfigPage from './Adam4080ConfigPage.vue';
interface Adam4000Module {
  module: string;
  address: string;
  param: string;
}

export default Vue.extend({
  components: {
    Adam4017ConfigPage,
    Adam4069ConfigPage,
    Adam4080ConfigPage,
  },
  props: {
    paramStr: {
      type: String,
      default: ``,
    },
  },
  data() {
    let config = Array<Adam4000Module>();
    config.push({
      module: '4017',
      address: '01',
      param: '',
    });

    if (this.paramStr !== '{}') {
      config = JSON.parse(this.paramStr);
    } else {
      this.$emit('param-changed', JSON.stringify(config));
    }

    return {
      index: 0,
      config,
      options: [
        { text: 'Adam4017', value: '4017' },
        { text: 'Adam4069', value: '4069' },
        { text: 'Adam4080', value: '4080' },
      ],
    };
  },
  methods: {
    moduleParamChange(module: Adam4000Module, v: string) {
      module.param = v;
      this.$emit('param-changed', JSON.stringify(this.config));
    },
    addModule() {
      let i = this.config.length;
      i++;
      this.config.push({
        module: '4017',
        address: ('00' + i).slice(-2),
        param: '',
      });
    },
  },
});
</script>
