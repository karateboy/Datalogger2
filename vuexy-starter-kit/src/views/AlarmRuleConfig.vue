<template>
  <div>
    <b-card title="警報規則管理" class="text-center">
      <b-row>
        <b-col class="p-1">
          <b-button
            v-ripple.400="'rgba(255, 255, 255, 0.15)'"
            variant="primary"
            class="mr-1"
            @click="newRule"
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
            @click="getAlarmRules"
          >
            取消
          </b-button>
          <b-button
            v-ripple.400="'rgba(255, 255, 255, 0.15)'"
            variant="danger"
            class="mr-1"
            @click="removeRule"
          >
            刪除
          </b-button>
        </b-col>
      </b-row>
      <b-table
        responsive
        :fields="columns"
        :items="alarmRules"
        select-mode="single"
        selectable
        selected-variant="info"
        bordered
        style="min-height: 750px"
        small
        @row-selected="onSelected"
      >
        <template #cell(enable)="row">
          <b-form-checkbox
            v-model="row.item.enable"
            switch
            @change="markDirty(row.item)"
          />
        </template>
        <template #cell(_id)="row">
          <b-form-input v-model="row.item._id" @change="markDirty(row.item)" />
        </template>
        <template #cell(monitors)="row">
          <v-select
            id="monitor"
            v-model="row.item.monitors"
            label="desc"
            :reduce="m => m._id"
            :options="monitors"
            multiple
            @input="markDirty(row.item)"
          />
        </template>
        <template #cell(monitorTypes)="row">
          <v-select
            id="monitorType"
            v-model="row.item.monitorTypes"
            label="desp"
            :reduce="mt => mt._id"
            :options="activatedMonitorTypes"
            multiple
            @input="markDirty(row.item)"
          />
        </template>
        <template #cell(tableTypes)="row">
          <v-select
            id="tableTypes"
            v-model="row.item.tableTypes"
            label="txt"
            :reduce="entry => entry.id"
            :options="tableTypes"
            multiple
            @input="markDirty(row.item)"
          />
        </template>
        <template #cell(max)="row">
          <b-form-input
            v-model.number="row.item.max"
            type="number"
            @change="markDirty(row.item)"
          />
        </template>
        <template #cell(min)="row">
          <b-form-input
            v-model.number="row.item.min"
            type="number"
            @change="markDirty(row.item)"
          />
        </template>
        <template #cell(alarmLevel)="row">
          <b-form-select
            v-model="row.item.alarmLevel"
            :options="alarmLevels"
            value-field="id"
            text-field="txt"
            required
            @change="markDirty(row.item)"
          ></b-form-select>
        </template>
        <template #cell(startTime)="row">
          <b-form-input
            v-model="row.item.startTime"
            type="time"
            size="sm"
            @change="markDirty(row.item)"
          />
        </template>
        <template #cell(endTime)="row">
          <b-form-input
            v-model="row.item.endTime"
            type="time"
            size="sm"
            @change="markDirty(row.item)"
          />
        </template>
      </b-table>
    </b-card>
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
import { Monitor } from '../store/monitors/types';
import { mapActions, mapState, mapGetters } from 'vuex';
import { isNumber } from 'highcharts';

interface AlarmRule {
  _id: string;
  monitorTypes: Array<string>;
  monitors: Array<string>;
  max?: number;
  min?: number;
  alarmLevel: number;
  enable: boolean;
  startTime?: string;
  endTime?: string;
  tableTypes: Array<string>;
}
interface EditAlarmRule extends AlarmRule {
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
        key: 'enable',
        label: '啟用',
      },
      {
        key: 'monitorTypes',
        label: '測項',
      },
      {
        key: 'monitors',
        label: '關注測站',
      },
      {
        key: 'tableTypes',
        label: '資料種類',
      },
      {
        key: 'alarmLevel',
        label: '等級',
      },
      {
        key: 'max',
        label: '上限',
      },
      {
        key: 'min',
        label: '下限',
      },
      {
        key: 'startTime',
        label: '開始時間',
      },
      {
        key: 'endTime',
        label: '結束時間',
      },
    ];
    const alarmRules = Array<EditAlarmRule>();

    return {
      columns,
      alarmLevels: [
        { id: 1, txt: '資訊' },
        { id: 2, txt: '警告' },
        { id: 3, txt: '嚴重' },
      ],
      tableTypes: [
        { txt: '小時資料', id: 'hour' },
        { txt: '分鐘資料', id: 'min' },
      ],
      alarmRules,
      selected: Array<AlarmRule>(),
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapGetters('monitorTypes', ['activatedMonitorTypes']),
    ...mapState('monitors', ['monitors']),
  },
  async mounted() {
    this.fetchMonitorTypes();
    this.fetchMonitors();
    this.getAlarmRules();
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors']),
    async getAlarmRules() {
      try {
        const res = await axios.get('/AlarmRules');
        if (res.status === 200) {
          this.alarmRules = res.data;
        }
      } catch (err) {
        console.error(`${err}`);
      }
    },
    justify(rule: AlarmRule) {
      if (!isNumber(rule.max)) rule.max = undefined;
      if (!isNumber(rule.min)) rule.min = undefined;
      if (!rule.startTime) rule.startTime = undefined;
      if (!rule.endTime) rule.endTime = undefined;
    },
    save() {
      const all = Array<Promise<any>>();
      for (const rule of this.alarmRules) {
        if (rule.dirty) {
          this.justify(rule);
          console.info(rule);
          all.push(axios.put(`/AlarmRule`, rule));
        }
      }

      Promise.all(all).then(() => {
        this.getAlarmRules();
        this.$bvModal.msgBoxOk('成功');
      });
    },
    markDirty(item: any) {
      item.dirty = true;
      console.info(item);
    },
    onSelected(items: Array<EditAlarmRule>) {
      this.selected = items;
    },
    newRule() {
      this.alarmRules.push({
        dirty: true,
        _id: `${this.alarmRules.length + 1}`,
        monitors: Array<string>(),
        monitorTypes: Array<string>(),
        alarmLevel: 1,
        enable: true,
        startTime: '0:00',
        endTime: '23:00',
        tableTypes: ['hour'],
      });
    },
    async removeRule() {
      const toBeDeletedRules = this.selected.map(p => p._id);
      const ret = await this.$bvModal.msgBoxConfirm(
        `請確認要刪除${toBeDeletedRules.join(',')}等規則`,
      );

      if (ret === true) {
        try {
          let allP = toBeDeletedRules.map(_id => {
            return axios.delete(`/AlarmRule/${_id}`);
          });
          await Promise.all(allP);
          this.getAlarmRules();
        } catch (err) {
          throw new Error('Failed to delete rules');
        }
      }
    },
  },
});
</script>
