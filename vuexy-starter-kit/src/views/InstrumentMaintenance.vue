<template>
  <b-card>
    <b-table
      :items="instList"
      :fields="fields"
      select-mode="single"
      selectable
      responsive
      @row-selected="onInstSelected"
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
              :disabled="selected.length === 0"
              @click="toggleMaintenanceMode"
            >
              切換維修
            </b-button>
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              variant="primary"
              class="mr-1"
              :disabled="selected.length === 0"
              @click="calibrateInstrumentZero"
            >
              零點校正
            </b-button>
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              variant="primary"
              class="mr-1"
              :disabled="selected.length === 0"
              @click="calibrateInstrumentSpan"
            >
              全幅校正
            </b-button>
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              variant="primary"
              class="mr-1"
              :disabled="selected.length === 0"
              @click="calibrateInstrument"
            >
              完整校正
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
  </b-card>
</template>
<script>
import Vue from 'vue';
import axios from 'axios';
const Ripple = require('vue-ripple-directive');
import ToastificationContent from '@core/components/toastification/ToastificationContent.vue';
import InstrumentWizard from './InstrumentWizard.vue';
export default Vue.extend({
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
        label: '儀器名稱',
        sortable: true,
      },
      {
        key: 'instType',
        label: '儀器種類',
        sortable: true,
      },
      {
        key: 'state',
        label: '狀態',
        sortable: true,
      },
      {
        key: 'protocol',
        label: '連線方式',
        sortable: true,
      },
      {
        key: 'protocolParam',
        label: '通訊參數',
        sortable: true,
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
      },
    ];

    return {
      fields,
      instList,
      isNew: true,
      selected: [],
      bit: 17,
      on: true,
    };
  },
  computed: {
    modalTitle() {
      return this.isNew ? '新增儀器' : '更新儀器設定';
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
  },
  mounted() {
    this.getInstList();
  },

  methods: {
    onSubmit(evt) {
      this.$bvModal.hide('instModal');
      this.$toast({
        component: ToastificationContent,
        props: {
          title: 'Form Submitted',
          icon: 'EditIcon',
          variant: 'success',
        },
      });
      this.getInstList();
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
        this.getInstList();
      }
    },
    async toggleActivateState() {
      try {
        let res = undefined;
        if (this.selected[0].state === '停用') {
          this.selected[0].state = '啟動中';
          res = await axios.put(
            `/ActivateInstrument/${this.selected[0]._id}`,
            {},
          );
        } else {
          this.selected[0].state = '停用中';
          res = await axios.put(
            `/DeactivateInstrument/${this.selected[0]._id}`,
            {},
          );
        }
        this.showResult(res.data.ok);
      } catch (err) {
        console.error(`${err}`);
        this.showResult(false);
      }
    },
    async toggleMaintenanceMode() {
      const res = await axios.put(
        `/ToggleMaintainInstrument/${this.selected[0]._id}`,
        {},
      );
      this.showResult(res.data.ok);
    },
    async calibrateInstrumentZero() {
      const res = await axios.put(
        `/CalibrateInstrumentZero/${this.selected[0]._id}`,
        {},
      );
      this.showResult(res.data.ok);
    },
    async calibrateInstrumentSpan() {
      const res = await axios.put(
        `/CalibrateInstrumentSpan/${this.selected[0]._id}`,
        {},
      );
      this.showResult(res.data.ok);
    },
    async calibrateInstrument() {
      const res = await axios.put(
        `/CalibrateInstrument/${this.selected[0]._id}`,
        {},
      );
      this.showResult(res.data.ok);
    },
    async resetInstrument() {
      const res = await axios.put(
        `/ResetInstrument/${this.selected[0]._id}`,
        {},
      );
      this.showResult(res.data.ok);
    },
    showWriteDoDlg() {
      this.$bvModal.show('writeDoModal');
    },
    deleteInst() {
      this.$bvModal
        .msgBoxConfirm(`是否要刪除儀器?${this.selected[0]._id}`, {
          okTitle: '是',
          cancelTitle: '否',
          centered: true,
        })
        .then(ret => {
          if (ret) {
            this.delInst(this.selected[0]._id);
          }
        })
        .catch(err => {
          throw Error(err);
        });
    },
    getInstList() {
      axios
        .get('/InstrumentInfos')
        .then(res => {
          this.instList = res.data;
        })
        .catch(err => {
          throw new Error(err);
        });
    },
    onInstSelected(items) {
      this.selected = items;
    },
    onRefresh() {
      this.getInstList();
    },
    onDeleted() {
      this.getInstList();
    },
  },
});
</script>
<style lang="scss">
@import '@core/scss/vue/libs/vue-wizard.scss';
@import '@core/scss/vue/libs/vue-select.scss';
</style>
