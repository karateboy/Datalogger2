<template>
  <div>
    <b-card title="數位訊號管理" class="text-center">
      <b-table
        small
        responsive
        :fields="columns"
        :items="monitorTypes"
        select-mode="multi"
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
        <template #cell(value)="row">
          {{ getMonitorTypeValue(row.item._id) }}
        </template>
        <template #cell(test)="row">
          <b-button
            variant="primary"
            @click="setSignalValue(row.item._id, true)"
            >設定</b-button
          >
          <b-button
            variant="primary"
            class="ml-2"
            @click="setSignalValue(row.item._id, false)"
            >清除</b-button
          >
        </template>
      </b-table>
      <b-row>
        <b-col>
          <b-button
            v-ripple.400="'rgba(255, 255, 255, 0.15)'"
            variant="info"
            class="mr-1"
            @click="addMt"
          >
            新增
          </b-button>
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
            @click="getSignalTypes"
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
      id="addSignalTypeModal"
      header-bg-variant="info"
      title="新增訊號"
      cancel-title="取消"
      ok-title="確認"
      @ok="addSignalType"
    >
      <b-form @submit.prevent>
        <b-form-group label="代碼" label-cols-md="3">
          <b-form-input v-model="form._id" />
        </b-form-group>
        <b-form-group label="名稱" label-cols-md="3">
          <b-form-input v-model="form.desp" />
        </b-form-group>
      </b-form>
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
import { MonitorType } from './types';

interface SignalMonitorType extends MonitorType {
  value?: boolean;
}

interface EditSignalMonitorType extends SignalMonitorType {
  dirty?: boolean;
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
        sortable: true,
      },
      {
        key: 'value',
        label: '現值',
      },
      {
        key: 'test',
        label: '測試',
      },
      {
        key: 'measuringBy',
        label: '儀器',
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
    const monitorTypes = Array<EditSignalMonitorType>();

    const form = {
      _id: 'SIGNAL1',
      desp: '訊號1',
    };
    return {
      display: false,
      columns,
      monitorTypes,
      form,
      selected: Array<EditSignalMonitorType>(),
      signalMap: new Map<string, SignalMonitorType>(),
    };
  },
  async mounted() {
    await this.getSignalValues();
  },
  methods: {
    async getSignalValues() {
      try {
        const res = await axios.get('/SignalValues');
        this.monitorTypes = res.data;
        this.signalMap.clear();
        for (let signal of this.monitorTypes) {
          this.signalMap.set(signal._id, signal);
        }
      } catch (err) {
        throw new Error('failed to get signal types');
      }
    },
    getMonitorTypeValue(mt: string) {
      if (!this.signalMap.has(mt)) return '??';
      else {
        let signal = this.signalMap.get(mt);
        if (signal?.measuringBy) {
          if (signal?.value === true) return '1';
          else if (signal?.value === false) return '0';
          else return '斷線';
        } else return '';
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
        this.getSignalValues();
        this.$bvModal.msgBoxOk('成功');
      });
    },
    markDirty(item: any) {
      item.dirty = true;
    },
    onMtSelected(items: Array<SignalMonitorType>) {
      this.selected = items;
    },
    async addMt() {
      this.form._id = `SIGNAL${this.monitorTypes.length + 1}`;
      this.form.desp = `訊號${this.monitorTypes.length + 1}`;
      this.$bvModal.show('addSignalTypeModal');
    },
    async addSignalType() {
      let mt: MonitorType = {
        _id: this.form._id,
        desp: this.form.desp,
        unit: 'N/A',
        prec: 2,
        order: 1000 + this.monitorTypes.length,
        signalType: true,
      };
      try {
        const resp = await axios.post(`/MonitorType/${mt._id}`, mt);
        if (resp.status === 200) this.getSignalValues();
      } catch (err) {
        throw new Error('failed to get signal types');
      }
    },
    async removeMt() {
      let deletedMts = this.selected.map(p => p._id);
      let ret = await this.$bvModal.msgBoxConfirm(
        `請確認要刪除${deletedMts.join(',')}測項`,
      );
      if (ret === true) {
        try {
          let allP = deletedMts.map(_id => {
            return axios.delete(`/MonitorType/${_id}`);
          });
          await Promise.all(allP);
          this.getSignalValues();
        } catch (err) {
          throw new Error('Failed to delete mt');
        }
      }
    },
    async setSignalValue(mt: string, bit: boolean) {
      try {
        const resp = await axios.get(`/SetSignal/${mt}/${bit}`);
      } catch (err) {
        throw new Error('failed to toggle mt');
      }
    },
  },
});
</script>

<style></style>
