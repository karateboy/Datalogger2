<template>
  <div>
    <b-card :title="$t('MonitorTypeConfig')" class="text-center">
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
        :per-page="10"
        :current-page="currentPage"
        style="max-height: 650px"
        @row-selected="onMtSelected"
      >
        <template #cell(desp)="row">
          <b-form-input v-model="row.item.desp" @change="markDirty(row.item)" />
        </template>
        <template #cell(more.rangeMin)="row">
          <b-form-input
            v-model.number="row.item.more.rangeMin"
            size="sm"
            @change="markDirty(row.item)"
          />
        </template>
        <template #cell(more.rangeMax)="row">
          <b-form-input
            v-model.number="row.item.more.rangeMax"
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
              markDirty(row.item)
              checkLevel(row.item.levelSeq)
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
            $t("save")
          </b-button>
          <b-button
            v-ripple.400="'rgba(186, 191, 199, 0.15)'"
            type="reset"
            variant="outline-secondary"
            class="mr-1"
            @click="getMonitorTypes"
          >
            $t("cancel")
          </b-button>
          <b-button
            v-ripple.400="'rgba(255, 255, 255, 0.15)'"
            variant="danger"
            class="mr-1"
            @click="removeMt"
          >
            $t("delete")
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
import Vue from 'vue'
import axios from 'axios'
import { MonitorType } from './types'
import { isNumber } from 'highcharts'

const Ripple = require('vue-ripple-directive')

interface EditMonitorType extends MonitorType {
  dirty?: boolean
  levelSeq?: string
}

export default Vue.extend({
  components: {},
  directives: {
    Ripple,
  },
  data() {
    const monitorTypes = Array<EditMonitorType>()

    const signalTypes = Array<MonitorType>()

    return {
      display: false,
      monitorTypes,
      signalTypes,
      editingMt: {
        thresholdConfig: {},
      },
      selected: Array<MonitorType>(),
      currentPage: 1,
    }
  },
  computed: {
    columns(): Array<any> {
      return [
        {
          key: '_id',
          label: '',
          formatter: (v: string) => {
            if (v === 'WD_SPEED' || v === 'WD_DIR') return `${v} (向量計算)`
            else return v
          },
        },
        {
          key: 'desp',
          label: this.$i18n.t('name'),
        },
        {
          key: 'unit',
          label: this.$i18n.t('unit'),
        },
        {
          key: 'prec',
          label: this.$i18n.t('precision'),
        },
        {
          key: 'order',
          label: this.$i18n.t('order'),
        },
        {
          key: 'std_law',
          label: 'HH Alarm',
        },
        {
          key: 'span',
          label: 'H Alarm',
        },
        {
          key: 'overLawSignalType',
          label: this.$i18n.t('overLawSignalType'),
          tdClass: { 'text-center': true },
        },
      ]
    },
  },
  async mounted() {
    await this.getMonitorTypes()
    await this.getSignalTypes()
  },
  methods: {
    async getMonitorTypes() {
      try {
        let res = await axios.get('/MonitorType')
        if (res.status === 200) {
          this.monitorTypes = res.data
          for (const mt of this.monitorTypes) {
            if (mt.levels !== undefined) {
              mt.levelSeq = mt.levels.join(',')
            }
            if (!mt.more) mt.more = {}
          }
        }
      } catch (error) {
        console.log(error)
      }
    },
    async getSignalTypes() {
      try {
        const res = await axios.get('/SignalTypes')
        this.signalTypes = res.data
      } catch (err) {
        throw new Error('failed to get signal types')
      }
    },
    justify(mt: any) {
      if (mt.span === '') mt.span = null
      if (mt.span_dev_internal === '') mt.span_dev_internal = null
      if (mt.span_dev_law === '') mt.span_dev_law = null
      if (mt.zd_internal === '') mt.zd_internal = null
      if (mt.zd_law === '') mt.zd_law = null
      if (mt.std_internal === '') mt.std_internal = null
      if (mt.std_law === '') mt.std_law = null
      if (mt.levelSeq) {
        try {
          let levelSeq = mt.levelSeq as string
          mt.levels = levelSeq.split(',').map(t => parseFloat(t))
        } catch (err) {}
      }

      if (!isNumber(mt.fixedB)) mt.fixedB = undefined
      if (!isNumber(mt.fixedM)) mt.fixedM = undefined
      if (!isNumber(mt.more.rangeMax)) mt.more.rangeMax = undefined
      if (!isNumber(mt.more.rangeMin)) mt.more.rangeMin = undefined
    },
    checkLevel(levelSeq: string | undefined): boolean {
      try {
        if (levelSeq === undefined) return true

        let levels = levelSeq.split(',').map(t => parseFloat(t))

        if (levels.length >= 1 && levels.every(l => !isNaN(l))) return true
        else {
          this.$bvModal.msgBoxOk(`${levelSeq}不是有效的分級!`)
          return false
        }
      } catch (err) {
        this.$bvModal.msgBoxOk(`${levelSeq}不是有效的分級!`)
        return false
      }
    },
    save() {
      const all = []
      for (const mt of this.monitorTypes) {
        if (mt.dirty) {
          this.justify(mt)
          all.push(axios.put(`/MonitorType`, mt))
        }
      }

      Promise.all(all).then(() => {
        this.getMonitorTypes()
        this.$bvModal.msgBoxOk('成功')
      })
    },
    markDirty(item: any) {
      item.dirty = true
    },
    onMtSelected(items: Array<MonitorType>) {
      this.selected = items
    },
    async removeMt() {
      let deletedMts = this.selected.map(p => p._id)
      let ret = await this.$bvModal.msgBoxConfirm(
        `請確認要刪除${deletedMts.join(',')}等測項`,
      )
      if (ret === true) {
        try {
          let allP = deletedMts.map(_id => {
            return axios.delete(`/MonitorType/${_id}`)
          })
          await Promise.all(allP)
          this.getMonitorTypes()
        } catch (err) {
          throw new Error('Failed to delete mt')
        }
      }
    },
  },
})
</script>

<style></style>
