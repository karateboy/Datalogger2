<template>
  <b-form @submit.prevent @change="onChange">
    <b-table
      :items="supportedMonitorTypes"
      :fields="fields"
      selectable
      responsive
      @row-selected="onSelected"
    >
      <template #cell(selected)="{ rowSelected }">
        <template v-if="rowSelected">
          <span aria-hidden="true">&check;</span>
          <span class="sr-only">Selected</span>
        </template>
        <template v-else>
          <span aria-hidden="true">&nbsp;</span>
          <span class="sr-only">Not selected</span>
        </template>
      </template>
    </b-table>
  </b-form>
</template>
<script lang="ts">
import Vue from 'vue';
import axios from 'axios';
interface DuoMonitorType {
  id: string;
  desc: string;
  configID: string;
}

interface DuoConfig {
  monitorTypes: Array<DuoMonitorType>;
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
    let monitorTypes = Array<DuoMonitorType>();
    let config: DuoConfig = {
      monitorTypes,
    };

    if (this.paramStr !== '{}') config = JSON.parse(this.paramStr);

    let supportedMonitorTypes = Array<DuoMonitorType>();

    let fields = [
      {
        key: 'selected',
        label: '選擇',
      },
      {
        key: 'desc',
        label: '測項名稱',
      },
      {
        key: 'isSpectrum',
        label: '頻譜',
        formatter: (v: boolean) => (v ? '是' : '否'),
      },
    ];
    return {
      config,
      supportedMonitorTypes,
      fields,
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
        const res = await axios.get('/DuoFixedMonitorTypes', {
          params: { host: this.host },
        });

        if (res.status === 200) {
          this.supportedMonitorTypes = res.data;
        }
      } catch (err) {
        throw new Error(`${err}`);
      }
    },
    onSelected(items: Array<DuoMonitorType>) {
      this.config.monitorTypes = items;
      this.onChange();
      this.onMonitorTypeChanged();
    },
    justify() {},
    onChange() {
      this.justify();
      this.$emit('param-changed', JSON.stringify(this.config));
    },
    async onMonitorTypeChanged() {
      try {
        const url = `/ConfigDuoMonitorTypes/${this.host}`;
        const res = await axios.post(url, this.config.monitorTypes);
        if (res.status !== 200) this.$bvModal.msgBoxOk('無法設定Duo');
      } catch (err) {
        throw new Error(`${err}`);
      }
    },
  },
});
</script>
