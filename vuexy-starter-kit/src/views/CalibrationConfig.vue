<template>
  <b-card>
    <b-table
      :items="calibrationList"
      :fields="fields"
      select-mode="single"
      selectable
      responsive
      @row-selected="onConfigSelected"
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
      <template #thead-top>
        <b-tr>
          <b-td colspan="8">
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              variant="primary"
              class="mr-1"
              @click="newCalibrationConfig"
            >
              新增
            </b-button>
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              variant="primary"
              class="mr-1"
              :disabled="selected.length === 0"
              @click="updateConfig"
            >
              變更
            </b-button>
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              variant="danger"
              class="mr-1"
              :disabled="selected.length === 0"
              @click="deleteConfig"
            >
              刪除
            </b-button>
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              variant="primary"
              class="mr-1"
              :disabled="selected.length === 0"
              @click="calibrateInstrument"
            >
              執行校正
            </b-button>
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              variant="primary"
              class="mr-1"
              :disabled="selected.length === 0"
              @click="resetInstrument"
            >
              中斷校正
            </b-button>
          </b-td>
        </b-tr>
      </template>
    </b-table>
    <b-modal
      id="calibrationConfigModal"
      :title="modalTitle"
      size="xl"
      modal-class="modal-primary"
      no-close-on-backdrop
      cancel-title="取消"
      ok-title="確定"
      @ok="onSubmit"
    >
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="12">
            <b-form-group
              label="多點校正名稱"
              label-for="calibration-id"
              label-cols-md="3"
            >
              <b-form-input
                id="calibration-id"
                v-model="activeConfig._id"
                placeholder="校正名稱"
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group
              label="設備ID"
              label-for="instrument-id"
              label-cols-md="3"
            >
              <v-select
                id="instrument-id"
                v-model="activeConfig.instrumentIds"
                label="_id"
                :reduce="inst => inst._id"
                :options="instList"
                :close-on-select="false"
                multiple
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group
              label="校正時間"
              label-for="calibration-time"
              label-cols-md="3"
            >
              <b-form-timepicker
                id="calibration-time"
                reset-button
                v-model="activeConfig.calibrationTime"
                label-no-time-selected="未指定時間"
                label-reset-button="清除"
              ></b-form-timepicker>
            </b-form-group>
          </b-col>
          <b-col>
            <b-table
              :items="activeConfig.pointConfigs"
              :fields="pointCalibrationFields"
              responsive
            >
              <template #cell(enable)="row">
                <b-form-checkbox v-model="row.item.enable" />
              </template>
              <template #cell(raiseTime)="row">
                <b-form-input
                  v-model.number="row.item.raiseTime"
                  type="number"
                />
              </template>
              <template #cell(holdTime)="row">
                <b-form-input
                  v-model.number="row.item.holdTime"
                  type="number"
                />
              </template>
              <template #cell(calibrateSeq)="row">
                <b-form-input v-model="row.item.calibrateSeq" />
              </template>
              <template #cell(calibrateDO)="row">
                <b-form-input v-model="row.item.calibrateDO" type="number" />
              </template>
              <template #cell(skipInternalVault)="row">
                <b-form-checkbox v-model="row.item.skipInternalVault" />
              </template>
              <template #cell(fullSpanPercent)="row">
                <b-form-input
                  v-if="row.index !== 6"
                  v-model.number="row.item.fullSpanPercent"
                  type="number"
                />
              </template>
              <template #cell(deviationAllowance)="row">
                <span v-if="row.index === 0 || row.index === 1"
                  >依測項管理設定</span
                >
                <b-form-input
                  v-else-if="row.index !== 6"
                  v-model.number="row.item.deviationAllowance"
                  type="number"
                />
              </template>
            </b-table>
          </b-col>
        </b-row>
      </b-form>
    </b-modal>
  </b-card>
</template>
<script>
import Vue from 'vue';
import axios from 'axios';
import ToastificationContent from '@core/components/toastification/ToastificationContent.vue';

const Ripple = require('vue-ripple-directive');

export default Vue.extend({
  components: {
    //InstrumentWizard,
  },
  directives: {
    Ripple,
  },
  data() {
    const instList = [];
    const fields = [
      {
        key: 'selected',
        label: '選擇',
      },
      {
        key: '_id',
        label: '校正名稱',
        sortable: true,
      },
      {
        key: 'instrumentIds',
        label: '儀器ID',
        sortable: true,
        formatter: (value, key, item) => {
          return item.instrumentIds.join(', ');
        },
      },
      {
        key: 'calibrationTime',
        label: '每日校正時間',
        sortable: true,
      },
      {
        key: 'monitorTypes',
        label: '測項',
        sortable: true,
        formatter: (value, key, item) => {
          let monitorTypes = item.instrumentIds.map(instId => {
            let inst = this.instList.find(inst => inst._id === instId);
            if (inst) {
              return inst.monitorTypes;
            } else return '';
          });
          return monitorTypes.join(', ');
        },
      },
    ];

    let activeConfig = {
      _id: '',
      instrumentIds: [],
      calibrationTime: null,
      pointConfigs: this.getDefaultPointConfigs(),
    };

    const pointCalibrationFields = [
      {
        key: 'enable',
        label: '啟用',
      },
      {
        key: 'name',
        label: '校正點',
      },
      {
        key: 'raiseTime',
        label: '上升時間(秒)',
      },
      {
        key: 'holdTime',
        label: '讀取時間(秒)',
      },
      {
        key: 'calibrateSeq',
        label: '校正器 Sequence',
      },
      {
        key: 'calibrateDO',
        label: 'DO 設定',
      },
      {
        key: 'skipInternalVault',
        label: '不切換校正電磁閥',
      },
      {
        key: 'fullSpanPercent',
        label: '設定值占全幅比(%)',
      },
      {
        key: 'deviationAllowance',
        label: '偏差允許(%)',
      },
    ];
    return {
      fields,
      calibrationList: [],
      isNew: true,
      selected: [],
      activeConfig,
      instList,
      pointCalibrationFields,
    };
  },
  computed: {
    modalTitle() {
      return this.isNew ? '新增校正' : '更新校正';
    },
    selectedInstrument() {
      if (!this.isNew && this.selected.length) return this.selected[0].inst;
      else return {};
    },
    canToggleActivate() {
      return (
        this.selected.length === 1 &&
        (this.selected[0].state !== '啟用中' ||
          this.selected[0].state === '停用中')
      );
    },
    toggleActivateName() {
      if (this.selected.length === 1) {
        if (this.selected[0].state === '停用') return '啟用';

        return '停用';
      }
      return '停用/啟用';
    },
    inststrumentMap() {
      let map = new Map();
      this.instList.forEach(inst => {
        map.set(inst._id, inst.monitorTypes);
      });
      return map;
    },
  },
  async mounted() {
    await this.getInstList();
    await this.getCalibrationConfigs();
  },

  methods: {
    async onSubmit(evt) {
      this.$bvModal.hide('calibrationConfigModal');
      if(this.activeConfig.calibrationTime === '') {
        this.activeConfig.calibrationTime = null;
      }
      await this.upsertConfig();
      await this.getCalibrationConfigs();
    },
    getDefaultPointConfigs() {
      return [
        {
          enable: true,
          name: '零點',
          raiseTime: 0,
          holdTime: 0,
          calibrateSeq: undefined,
          calibrateDO: undefined,
          skipInternalVault: false,
          fullSpanPercent: 0,
          deviationAllowance: 5,
        },
        {
          enable: true,
          name: '全幅',
          raiseTime: 0,
          holdTime: 0,
          calibrateSeq: undefined,
          calibrateDO: undefined,
          skipInternalVault: false,
          fullSpanPercent: 100,
          deviationAllowance: 5,
        },
        {
          enable: false,
          name: '校正點3',
          raiseTime: 0,
          holdTime: 0,
          calibrateSeq: undefined,
          calibrateDO: undefined,
          skipInternalVault: false,
          fullSpanPercent: 50,
          deviationAllowance: 5,
        },
        {
          enable: false,
          name: '校正點4',
          raiseTime: 0,
          holdTime: 0,
          calibrateSeq: undefined,
          calibrateDO: undefined,
          skipInternalVault: false,
          fullSpanPercent: 50,
          deviationAllowance: 5,
        },
        {
          enable: false,
          name: '校正點5',
          raiseTime: 0,
          holdTime: 0,
          calibrateSeq: undefined,
          calibrateDO: undefined,
          skipInternalVault: false,
          fullSpanPercent: 50,
          deviationAllowance: 5,
        },
        {
          enable: false,
          name: '校正點6',
          raiseTime: 0,
          holdTime: 0,
          calibrateSeq: undefined,
          calibrateDO: undefined,
          skipInternalVault: false,
          fullSpanPercent: 50,
          deviationAllowance: 5,
        },
        {
          enable: false,
          name: '校正後Purge',
          raiseTime: 0,
          holdTime: 0,
          calibrateSeq: undefined,
          calibrateDO: undefined,
          skipInternalVault: false,
          fullSpanPercent: 0,
          deviationAllowance: 0,
        },
      ];
    },
    showResult(ok) {
      if (ok) {
        this.$toast({
          component: ToastificationContent,
          props: {
            title: '成功',
            icon: 'EditIcon',
            variant: 'success',
          },
        });
      } else {
        this.$toast({
          component: ToastificationContent,
          props: {
            title: '失敗',
            icon: 'EditIcon',
            variant: 'danger',
          },
        });
        this.getCalibrationConfigs();
      }
    },
    async calibrateInstrument() {
      const res = await axios.put(
        `/ExecuteCalibration/${this.selected[0]._id}`,
        {},
      );
      this.showResult(res.data.ok);
    },
    async resetInstrument() {
      const res = await axios.put(
        `/CancelCalibration/${this.selected[0]._id}`,
        {},
      );
      this.showResult(res.data.ok);
    },
    newCalibrationConfig() {
      this.isNew = true;
      this.activeConfig = {
        _id: `多點校正${this.calibrationList.length + 1}`,
        instrumentIds: [],
        calibrationTime: '01:00:00',
        pointConfigs: this.getDefaultPointConfigs(),
      };
      this.$bvModal.show('calibrationConfigModal');
    },
    updateConfig() {
      this.isNew = false;
      this.activeConfig = this.selected[0];
      this.$bvModal.show('calibrationConfigModal');
    },
    deleteConfig() {
      this.$bvModal
        .msgBoxConfirm(`是否要刪除校正設定?${this.selected[0]._id}`, {
          okTitle: '是',
          cancelTitle: '否',
          centered: true,
        })
        .then(ret => {
          if (ret) {
            this.delConfig(this.selected[0]._id);
          }
        })
        .catch(err => {
          throw Error(err);
        });
    },
    async getCalibrationConfigs() {
      let res = await axios.get('/CalibrationConfig');
      this.calibrationList = res.data;
    },
    async upsertConfig() {
      let res = await axios.post('/CalibrationConfig', this.activeConfig);
      this.$toast({
        component: ToastificationContent,
        props: {
          title: res.data.ok ? '成功' : '失敗',
          icon: 'UserIcon',
          variant: res.data.ok ? 'success' : 'danger',
        },
      });
    },
    async delConfig(id) {
      let res = await axios.delete(`/CalibrationConfig/${id}`);
      const ret = res.data;
      if (ret.ok) {
        this.$toast({
          component: ToastificationContent,
          props: {
            title: '成功',
            icon: 'UserIcon',
          },
        });
      } else {
        this.$toast({
          component: ToastificationContent,
          props: {
            title: '刪除失敗',
            icon: 'UserIcon',
          },
        });
      }
      await this.getCalibrationConfigs();
    },
    onConfigSelected(items) {
      this.selected = items;
    },
    onUpdate() {
      this.$bvModal.hide('calibrationConfigModal');
      this.getCalibrationConfigs();
    },
    onRefresh() {
      this.getCalibrationConfigs();
    },
    onDeleted() {
      this.getCalibrationConfigs();
    },
    async getInstList() {
      let res = await axios.get('/InstrumentInfos');
      this.instList = res.data;
    },
  },
});
</script>
<style lang="scss">
@import '@core/scss/vue/libs/vue-wizard.scss';
@import '@core/scss/vue/libs/vue-select.scss';
</style>
