<template>
  <div>
    <b-card title="測項管理" class="text-center">
      <b-table
        responsive
        :fields="columns"
        :items="monitorTypes"
        bordered
        sticky-header
        style="max-height: 600px"
      >
        <template #cell(desp)="row">
          <b-form-input v-model="row.item.desp" @change="markDirty(row.item)" />
        </template>
        <template #cell(unit)="row">
          <b-form-input
            v-model="row.item.unit"
            size="sm"
            @change="markDirty(row.item)"
          />
        </template>
        <template #cell(prec)="row">
          <b-form-input
            v-model.number="row.item.prec"
            size="sm"
            @change="markDirty(row.item)"
          />
        </template>
        <!--  <template #cell(std_internal)="row">
          <b-form-input
            v-model.number="row.item.std_internal"
            size="sm"
            @change="markDirty(row.item)"
          />
        </template> -->
        <template #cell(std_law)="row">
          <b-form-input
            v-model.number="row.item.std_law"
            size="sm"
            @change="markDirty(row.item)"
          />
        </template>
        <template #cell(thresholdConfig)="row">
          <b-form-input
            v-model.number="row.item.thresholdConfig.elapseTime"
            size="sm"
            @change="markDirty(row.item)"
          />
        </template>
        <!-- <template #cell(zd_internal)="row">
          <b-form-input
            v-model.number="row.item.zd_internal"
            size="sm"
            @change="markDirty(row.item)"
          />
        </template> -->
        <template #cell(zd_law)="row">
          <b-form-input
            v-model.number="row.item.zd_law"
            size="sm"
            @change="markDirty(row.item)"
          />
        </template>
        <template #cell(span)="row">
          <b-form-input
            v-model.number="row.item.span"
            type="number"
            size="sm"
            @change="markDirty(row.item)"
          />
        </template>
        <!-- <template #cell(span_dev_internal)="row">
          <b-form-input
            v-model.number="row.item.span_dev_internal"
            size="sm"
            @change="markDirty(row.item)"
          />
        </template> -->
        <template #cell(span_dev_law)="row">
          <b-form-input
            v-model.number="row.item.span_dev_law"
            size="sm"
            @change="markDirty(row.item)"
          />
        </template>
      </b-table>
      <b-row>
        <b-col>
          <b-button
            v-ripple.400="'rgba(255, 255, 255, 0.15)'"
            variant="primary"
            class="mr-1"
            @click="save"
          >
            儲存
          </b-button>
          <b-button
            v-ripple.400="'rgba(186, 191, 199, 0.15)'"
            type="reset"
            variant="outline-secondary"
            @click="getMonitorTypes"
          >
            取消
          </b-button>
        </b-col>
      </b-row>
    </b-card>
    <b-modal
      id="thresholdConfig"
      title="高值處理設定"
      cancel-title="取消"
      ok-title="確認"
      @ok="setMtThresholdConfig"
    >
      <b-form> asdf </b-form>
    </b-modal>
  </div>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script lang="ts">
import Vue from 'vue';
import Ripple from 'vue-ripple-directive';
import axios from 'axios';
/*
interface MonitorType {
  _id: string;
  desp: string;
  unit: string;
  prec: number;
  order: number;
  signalType: boolean;
  std_law?: number;
  std_internal?: number;
  zd_internal?: number;
  zd_law?: number;
  span?: number;
  span_dev_internal?: number;
  span_dev_law?: number;
  measuringBy?: Array<string>;
} */

export default Vue.extend({
  components: {},
  directives: {
    Ripple,
  },
  data() {
    const columns = [
      {
        key: '_id',
        label: '代碼',
      },
      {
        key: 'desp',
        label: '名稱',
        sortable: true,
      },
      {
        key: 'unit',
        label: '單位',
        sortable: true,
      },
      {
        key: 'prec',
        label: '小數點位數',
        sortable: true,
      },
      /*
      {
        key: 'std_internal',
        label: '內控值',
        sortable: true,
      },*/
      {
        key: 'std_law',
        label: '法規值',
        sortable: true,
      },
      {
        key: 'thresholdConfig',
        label: '超標噴水時間',
        sortable: true,
      },
      /*
      {
        key: 'zd_internal',
        label: '零點偏移內控',
      },*/
      {
        key: 'zd_law',
        label: '零點偏移法規',
      },
      {
        key: 'span',
        label: '全幅值',
      },
      /*
      {
        key: 'span_dev_internal',
        label: '全幅偏移內控',
      },*/
      {
        key: 'span_dev_law',
        label: '全幅值偏移法規',
      },
    ];
    const monitorTypes = [];

    const form = {
      thresholdConfig: {
        elapseTime: 30,
      },
    };
    return {
      display: false,
      columns,
      monitorTypes,
      editingMt: undefined,
      form,
    };
  },
  mounted() {
    this.getMonitorTypes();
  },
  methods: {
    getMonitorTypes() {
      axios.get('/MonitorType').then(res => {
        this.monitorTypes = res.data;
        for (const mt of this.monitorTypes) {
          if (mt.thresholdConfig === undefined)
            mt.thresholdConfig = { elapseTime: 30 };
        }
      });
    },
    configThreshold(item) {
      this.editingMt = item;
      this.$bvModal.show('thresholdConfig');
    },
    showThresholdConfig(config) {
      if (!config) return '-';
      else {
        return `持續時間 ${config.elapseTime}`;
      }
    },
    justify(mt) {
      if (mt.span === '') mt.span = null;
      if (mt.span_dev_internal === '') mt.span_dev_internal = null;
      if (mt.span_dev_law === '') mt.span_dev_law = null;
      if (mt.zd_internal === '') mt.zd_internal = null;
      if (mt.zd_law === '') mt.zd_law = null;
      if (mt.std_internal === '') mt.std_internal = null;
      if (mt.std_law === '') mt.std_law = null;
    },
    save() {
      const all = [];
      for (const mt of this.monitorTypes) {
        if (mt.dirty) {
          this.justify(mt);
          all.push(axios.put(`/MonitorType/${mt._id}`, mt));
        }
      }

      Promise.all(all).then(() => {
        this.getMonitorTypes();
        this.$bvModal.msgBoxOk('成功');
      });
    },
    markDirty(item) {
      item.dirty = true;
    },
    setMtThresholdConfig() {
      this.editingMt.thresholdConfig = this.form.thresholdConfig;
      this.markDirty(this.editingMt);
    },
  },
});
</script>

<style></style>
