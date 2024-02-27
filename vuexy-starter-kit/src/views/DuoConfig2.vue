<template>
  <b-form @submit.prevent @change="onChange">
    <b-table
      ref="duoMonitorTypes"
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

interface EditRow extends DuoMonitorType {
  rowSelected?: boolean;
}

interface DuoConfig {
  fixed: boolean;
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
      fixed: true,
      monitorTypes,
    };

    if (this.paramStr !== '{}') config = JSON.parse(this.paramStr);

    let supportedMonitorTypes = Array<EditRow>();

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
        let duoMonitorTypes = this.$refs.duoMonitorTypes as any;
        this.supportedMonitorTypes.forEach((t, idx) => {
          if (
            this.config.monitorTypes.find(mt => mt.id === t.id) !== undefined
          ) {
            duoMonitorTypes.selectRow(idx);
          }
        });
      }
    },
  },
  mounted() {
    this.getFixedMonitorTypes();
  },
  methods: {
    async getFixedMonitorTypes() {
      try {
        const res = await axios.get('/DuoFixedMonitorTypes');

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
    },
    justify() {},
    onChange() {
      this.justify();
      this.$emit('param-changed', JSON.stringify(this.config));
    },
  },
});
</script>
