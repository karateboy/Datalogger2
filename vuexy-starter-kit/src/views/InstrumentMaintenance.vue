<template>
  <b-card>
    <b-table
      :items="instList"
      :fields="fields"
      select-mode="single"
      selectable
      selected-variant=""
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
              切換保養
            </b-button>
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              variant="primary"
              class="mr-1"
              :disabled="selected.length === 0"
              @click="toggleRepairMode"
            >
              切換維修
            </b-button>
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              variant="primary"
              class="mr-1"
              :disabled="selected.length === 0"
              @click="toggleState('023')"
            >
              切換查核
            </b-button>
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              variant="primary"
              class="mr-1"
              :disabled="selected.length === 0"
              @click="confirmToggle('022')"
            >
              切換氣象及粒狀物校正
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
    <b-table :items="readers" :fields="reader_fields">
      <template #thead-top>
        <tr>
          <td colspan="3"><h3>檔案資料讀取</h3></td>
        </tr>
        <tr>
          <td></td>
          <td colspan="2">
            <b-row>
              <b-col cols="9">
                <b-form-group
                  label="VOC狀態"
                  label-for="monitor"
                  label-cols-md="3"
                >
                  <v-select
                    id="VOC-state"
                    v-model="form.vocState"
                    label="name"
                    :reduce="state => state.code"
                    :options="vocStates"
                    :close-on-select="false"
                  />
                </b-form-group>
              </b-col>
              <b-col cols="3">
                <b-button
                  v-ripple.400="'rgba(255, 255, 255, 0.15)'"
                  type="submit"
                  variant="primary"
                  class="mr-1"
                  @click="setVocState"
                >
                  設定
                </b-button>
              </b-col>
            </b-row>
          </td>
        </tr>
      </template>
    </b-table>
  </b-card>
</template>
<script>
import Vue from 'vue'
import axios from 'axios'
const Ripple = require('vue-ripple-directive')
import ToastificationContent from '@core/components/toastification/ToastificationContent.vue'
import InstrumentWizard from './InstrumentWizard.vue'
export default Vue.extend({
  directives: {
    Ripple,
  },
  data() {
    let instList = []
    const fields = [
      {
        key: 'selected',
        label: '選擇',
      },
      {
        key: '_id',
        label: '儀器ID',
        sortable: true,
        tdClass: (value, key, item) => {
          return item.classStr
        },
      },
      {
        key: 'instType',
        label: '儀器種類',
        sortable: true,
        tdClass: (value, key, item) => {
          return item.classStr
        },
      },
      {
        key: 'state',
        label: '狀態',
        sortable: true,
        tdClass: (value, key, item) => {
          return item.classStr
        },
      },
      {
        key: 'protocol',
        label: '連線方式',
        sortable: true,
        tdClass: (value, key, item) => {
          return item.classStr
        },
      },
      {
        key: 'protocolParam',
        label: '通訊參數',
        sortable: true,
        tdClass: (value, key, item) => {
          return item.classStr
        },
      },
      {
        key: 'calibrationTime',
        label: '預設校正時間',
        sortable: true,
        tdClass: (value, key, item) => {
          return item.classStr
        },
      },
      {
        key: 'monitorTypes',
        label: '測項',
        sortable: true,
        tdClass: (value, key, item) => {
          return item.classStr
        },
      },
    ]

    const reader_fields = [
      {
        key: 'name',
        label: '功能模組',
        tdClass: (value, key, item) => {
          return form.vocCss
        },
      },
      {
        key: 'path',
        label: '路徑',
        tdClass: (value, key, item) => {
          return form.vocCss
        },
      },
      {
        key: 'setting',
        label: '設定',
        tdClass: (value, key, item) => {
          return form.vocCss
        },
      },
    ]
    const form = {
      vocState: '010',
      vocCss: [],
    }
    let readers = []
    let vocStates = [
      {
        name: '正常',
        code: '010',
      },
      {
        name: '儀器未啟用',
        code: '000',
      },
      {
        name: '查核',
        code: '023',
      },
      {
        name: '維修',
        code: '031',
      },
      {
        name: '保養',
        code: '033',
      },
    ]
    return {
      fields,
      instList,
      isNew: true,
      selected: [],
      bit: 17,
      on: true,
      reader_fields,
      readers,
      form,
      vocStates,
    }
  },
  computed: {
    modalTitle() {
      return this.isNew ? '新增儀器' : '更新儀器設定'
    },
    selectedInstrument() {
      if (!this.isNew && this.selected.length) return this.selected[0].inst
      else return {}
    },
    canToggleActivate() {
      return (
        this.selected.length === 1 &&
        (this.selected[0].state !== '啟用中' ||
          this.selected[0].state === '停用中')
      )
    },
    toggleActivateName() {
      if (this.selected.length === 1) {
        if (this.selected[0].state === '停用') return '啟用'

        return '停用'
      }
      return '停用/啟用'
    },
  },
  async mounted() {
    this.getInstList()
    await this.getReaderInfos()
    await this.getVocState()
  },

  methods: {
    onSubmit(evt) {
      this.$bvModal.hide('instModal')
      this.$toast({
        component: ToastificationContent,
        props: {
          title: 'Form Submitted',
          icon: 'EditIcon',
          variant: 'success',
        },
      })
      this.getInstList()
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
        })
      } else {
        this.$toast({
          component: ToastificationContent,
          props: {
            title: '失敗',
            icon: 'EditIcon',
            variant: 'danger',
          },
        })
      }
      this.getInstList()
    },
    async toggleActivateState() {
      try {
        let res = undefined
        if (this.selected[0].state === '停用') {
          this.selected[0].state = '啟動中'
          res = await axios.put(
            `/ActivateInstrument/${this.selected[0]._id}`,
            {},
          )
        } else {
          this.selected[0].state = '停用中'
          res = await axios.put(
            `/DeactivateInstrument/${this.selected[0]._id}`,
            {},
          )
        }
        this.showResult(res.data.ok)
      } catch (err) {
        console.error(`${err}`)
        this.showResult(false)
      }
    },
    async toggleState(state) {
      try {
        const res = await axios.put(
          `/ToggleInstrumentState/${this.selected[0]._id}/${state}`,
          {},
        )
        this.showResult(res.data.ok)
      } catch (err) {
        console.error(err)
      }
    },
    async confirmToggle(state) {
      const value = await this.$bvModal.msgBoxConfirm(
        `目前選擇的儀器:${this.selected[0]._id}, 氣象及粒狀物校正僅適用於相關測項, 是否確認切換?`,
      )
      if (value) {
        await this.toggleState(state)
      }
    },
    async toggleMaintenanceMode() {
      const res = await axios.put(
        `/ToggleMaintainInstrument/${this.selected[0]._id}`,
        {},
      )
      this.showResult(res.data.ok)
    },
    async toggleRepairMode() {
      const res = await axios.put(
        `/ToggleRepairInstrument/${this.selected[0]._id}`,
        {},
      )
      this.showResult(res.data.ok)
    },
    async calibrateInstrumentZero() {
      const res = await axios.put(
        `/CalibrateInstrumentZero/${this.selected[0]._id}`,
        {},
      )
      this.showResult(res.data.ok)
    },
    async calibrateInstrumentSpan() {
      const res = await axios.put(
        `/CalibrateInstrumentSpan/${this.selected[0]._id}`,
        {},
      )
      this.showResult(res.data.ok)
    },
    async calibrateInstrument() {
      const res = await axios.put(
        `/CalibrateInstrument/${this.selected[0]._id}`,
        {},
      )
      this.showResult(res.data.ok)
    },
    async resetInstrument() {
      const res = await axios.put(
        `/ResetInstrument/${this.selected[0]._id}`,
        {},
      )
      this.showResult(res.data.ok)
    },
    showWriteDoDlg() {
      this.$bvModal.show('writeDoModal')
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
            this.delInst(this.selected[0]._id)
          }
        })
        .catch(err => {
          throw Error(err)
        })
    },
    async getInstList() {
      try {
        let res = await axios.get('/InstrumentInfos')
        if (res.status === 200) {
          this.instList = res.data
          console.info('getInstList', this.instList)
        }
      } catch (err) {
        console.error(err)
      }
    },
    onInstSelected(items) {
      this.selected = items
    },
    onRefresh() {
      this.getInstList()
    },
    onDeleted() {
      this.getInstList()
    },
    async getReaderInfos() {
      try {
        let res = await axios.get('/ReaderInfos ')
        if (res.status === 200) {
          console.info('readerInfos', res.data)
          this.readers = res.data
        }
      } catch (err) {
        console.error(err)
      }
    },
    async getVocState() {
      try {
        let res = await axios.get('/VocState')
        if (res.status === 200) {
          console.info('vocState', res.data)
          this.form.vocState = res.data.state
          this.form.vocCss = res.data.cssStr
        }
      } catch (err) {
        console.error(err)
      }
    },
    async setVocState() {
      try {
        const res = await axios.put(`/VocState/${this.form.vocState}`, {})
        this.showResult(res.data.ok)
        await this.getVocState()
      } catch (err) {
        console.error(err)
      }
    },
  },
})
</script>
<style lang="scss">
@import '@core/scss/vue/libs/vue-wizard.scss';
@import '@core/scss/vue/libs/vue-select.scss';
</style>
