<template>
  <div>
    <b-card title="測項管理" class="text-center">
      <b-table
        small
        responsive
        :fields="columns"
        :items="monitorTypes"
        select-mode="single"
        selectable
        selected-variant="info"
        bordered
        sticky-header
        style="max-height: 650px"
        @row-selected="onMtSelected"
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
        <template #cell(order)="row">
          <b-form-input
            v-model.number="row.item.order"
            size="sm"
            @change="markDirty(row.item)"
          />
        </template>
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
        <template #cell(span_dev_law)="row">
          <b-form-input
            v-model.number="row.item.span_dev_law"
            size="sm"
            @change="markDirty(row.item)"
          />
        </template>
        <template #cell(levelSeq)="row">
          <b-form-input
            v-model="row.item.levelSeq"
            size="sm"
            @change="
              markDirty(row.item);
              checkLevel(row.item.levelSeq);
            "
          />
        </template>
        <template #cell(calbrate)="row">
          <b-form-checkbox
            v-model="row.item.calibrate"
            switch
            @change="markDirty(row.item)"
          />
        </template>
        <template #cell(fixedM)="row">
          <b-form-input
            v-model.number="row.item.fixedM"
            type="number"
            size="sm"
            @change="markDirty(row.item)"
          />
        </template>
        <template #cell(fixedB)="row">
          <b-form-input
            v-model.number="row.item.fixedB"
            type="number"
            size="sm"
            @change="markDirty(row.item)"
          />
        </template>
        <template #cell(accumulated)="row">
          <b-form-checkbox
            v-model="row.item.accumulated"
            switch
            @change="markDirty(row.item)"
          />
        </template>
        <template #cell(overLawSignalType)="row">
          <v-select
            v-model="row.item.overLawSignalType"
            label="desp"
            :reduce="mt => mt._id"
            :options="signalTypes"
            @input="markDirty(row.item)"
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
            class="mr-1"
            @click="getMonitorTypes"
          >
            取消
          </b-button>
          <b-button
            v-ripple.400="'rgba(255, 255, 255, 0.15)'"
            variant="danger"
            class="mr-1"
            @click="removeMt"
          >
            刪除
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
    </b-modal>
  </div>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script lang="ts">
import Vue from 'vue';
const Ripple = require('vue-ripple-directive');
import axios from 'axios';
import { MonitorType, ThresholdConfig } from './types';
import { isNumber } from 'highcharts';

interface EditMonitorType extends MonitorType {
  dirty?: boolean;
  levelSeq?: string;
}

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
        formatter: (v: string) => {
          if (v === 'WD_SPEED' || v === 'WD_DIR') return `${v} (向量計算)`;
          else return v;
        },
      },
      {
        key: 'desp',
        label: '名稱',
      },
      {
        key: 'unit',
        label: '單位',
      },
      {
        key: 'prec',
        label: '小數點位數',
      },
      {
        key: 'order',
        label: '順序',
      },
      {
        key: 'std_law',
        label: '法規值',
      },
      {
        key: 'zd_law',
        label: '零點偏移法規',
      },
      {
        key: 'span',
        label: '全幅值',
      },
      {
        key: 'span_dev_law',
        label: '全幅值偏移法規',
      },
      {
        key: 'levelSeq',
        label: '分級(以逗點分隔)',
      },
      {
        key: 'calbrate',
        label: '校正回歸',
        tdClass: { 'text-center': true },
      },
      {
        key: 'fixedM',
        label: 'M',
        tdClass: { 'text-center': true },
      },
      {
        key: 'fixedB',
        label: 'B',
        tdClass: { 'text-center': true },
      },
      {
        key: 'accumulated',
        label: '累積',
        tdClass: { 'text-center': true },
      },
      {
        key: 'overLawSignalType',
        label: '超標數位訊號',
        tdClass: { 'text-center': true },
      },
      {
        key: 'measuringBy',
        label: '測量儀器',
        formatter: (
          value: null | Array<string>,
          key: string,
          item: Array<string>,
        ) => {
          if (value !== null) return `${value.join(', ')}`;
          else return '';
        },
      },
    ];
    const monitorTypes = Array<EditMonitorType>();

    const signalTypes = Array<MonitorType>();
    let thresholdConfig: ThresholdConfig = {
      elapseTime: 30,
    };

    const form = {
      thresholdConfig,
    };
    return {
      display: false,
      columns,
      monitorTypes,
      signalTypes,
      editingMt: {
        thresholdConfig: {},
      },
      form,
      selected: Array<MonitorType>(),
    };
  },
  async mounted() {
    this.getMonitorTypes();
    await this.getSignalTypes();
  },
  methods: {
    getMonitorTypes() {
      axios.get('/MonitorType').then(res => {
        this.monitorTypes = res.data;
        for (const mt of this.monitorTypes) {
          if (mt.levels !== undefined) {
            mt.levelSeq = mt.levels.join(',');
          }
        }
      });
    },
    async getSignalTypes() {
      try {
        const res = await axios.get('/SignalTypes');
        this.signalTypes = res.data;
      } catch (err) {
        throw new Error('failed to get signal types');
      }
    },
    justify(mt: any) {
      if (mt.span === '') mt.span = null;
      if (mt.span_dev_internal === '') mt.span_dev_internal = null;
      if (mt.span_dev_law === '') mt.span_dev_law = null;
      if (mt.zd_internal === '') mt.zd_internal = null;
      if (mt.zd_law === '') mt.zd_law = null;
      if (mt.std_internal === '') mt.std_internal = null;
      if (mt.std_law === '') mt.std_law = null;
      if (mt.levelSeq) {
        try {
          let levelSeq = mt.levelSeq as string;
          let levels = levelSeq.split(',').map(t => parseFloat(t));
          mt.levels = levels;
        } catch (err) {}
      }

      if (!isNumber(mt.fixedB)) mt.fixedB = undefined;
      if (!isNumber(mt.fixedM)) mt.fixedM = undefined;
    },
    checkLevel(levelSeq: string | undefined): boolean {
      try {
        if (levelSeq === undefined) return true;

        let levels = levelSeq.split(',').map(t => parseFloat(t));

        if (levels.length >= 1 && levels.every(l => !isNaN(l))) return true;
        else {
          this.$bvModal.msgBoxOk(`${levelSeq}不是有效的分級!`);
          return false;
        }
      } catch (err) {
        this.$bvModal.msgBoxOk(`${levelSeq}不是有效的分級!`);
        return false;
      }
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
    markDirty(item: any) {
      item.dirty = true;
    },
    setMtThresholdConfig() {
      this.editingMt.thresholdConfig = this.form.thresholdConfig;
      this.markDirty(this.editingMt);
    },
    onMtSelected(items: Array<MonitorType>) {
      this.selected = items;
    },
    async removeMt() {
      let deletedMts = this.selected.map(p => p._id);
      let ret = await this.$bvModal.msgBoxConfirm(
        `請確認要刪除${deletedMts.join(',')}等測項`,
      );
      if (ret === true) {
        try {
          let allP = deletedMts.map(_id => {
            return axios.delete(`/MonitorType/${_id}`);
          });
          await Promise.all(allP);
          this.getMonitorTypes();
        } catch (err) {
          throw new Error('Failed to delete mt');
        }
      }
    },
  },
});
</script>

<style></style>
