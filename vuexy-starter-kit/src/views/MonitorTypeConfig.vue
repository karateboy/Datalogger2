<template>
  <div>
    <b-card title="測項管理" class="text-center">
      <b-table
          small
          responsive
          :fields="columns"
          :items="monitorTypes"
          selectable
          selected-variant="info"
          bordered
          sticky-header
          :per-page="10"
          :current-page="currentPage"
          style="max-height: 650px"
          @row-selected="onMtSelected"
      >
        <template #cell(desp)="row">
          <b-form-input v-model="row.item.desp" @change="markDirty(row.item)"/>
        </template>
        <template #cell(more.hAlarm)="row">
          <b-form-input
              v-model.number="row.item.more.hAlarm"
              size="sm"
              @change="markDirty(row.item)"
          />
        </template>
        <template #cell(more.hhAlarm)="row">
          <b-form-input
              v-model.number="row.item.more.hhAlarm"
              size="sm"
              @change="markDirty(row.item)"
          />
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
      </b-table>
      <b-pagination
          v-model="currentPage"
          :total-rows="monitorTypes.length"
          :per-page="10"
          first-text="⏮"
          prev-text="⏪"
          next-text="⏩"
          last-text="⏭"
          class="mt-4"
      ></b-pagination>
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
  </div>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script lang="ts">
import Vue from 'vue';
import axios from 'axios';
import {MonitorType} from './types';
import {isNumber} from 'highcharts';

const Ripple = require('vue-ripple-directive');

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
        key: 'more.hAlarm',
        label: 'High Alarm',
      },
      {
        key: 'more.hhAlarm',
        label: 'High High Alarm',
      },
      {
        key: 'std_law',
        label: '法規值',
      },
      {
        key: 'std_law',
        label: '法規值',
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

    return {
      display: false,
      columns,
      monitorTypes,
      signalTypes,
      editingMt: {
        thresholdConfig: {},
      },
      selected: Array<MonitorType>(),
      currentPage: 1
    };
  },
  async mounted() {
    await this.getMonitorTypes();
    await this.getSignalTypes();
  },
  methods: {
    async getMonitorTypes() {
      try {
        let res = await axios.get('/MonitorType');
        if (res.status === 200) {
          this.monitorTypes = res.data;
          for (const mt of this.monitorTypes) {
            if (mt.levels !== undefined) {
              mt.levelSeq = mt.levels.join(',');
            }
            if (!mt.more)
              mt.more = {};

          }
        }
      } catch (error) {
        console.log(error);
      }
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
          mt.levels = levelSeq.split(',').map(t => parseFloat(t));
        } catch (err) {
        }
      }

      if (!isNumber(mt.fixedB)) mt.fixedB = undefined;
      if (!isNumber(mt.fixedM)) mt.fixedM = undefined;
      if (!isNumber(mt.more.rangeMax)) mt.more.rangeMax = undefined;
      if (!isNumber(mt.more.rangeMin)) mt.more.rangeMin = undefined;
      if (!isNumber(mt.more.hAlarm)) mt.more.hAlarm = undefined;
      if (!isNumber(mt.more.hhAlarm)) mt.more.hhAlarm = undefined;
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
          all.push(axios.put(`/MonitorType`, mt));
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
