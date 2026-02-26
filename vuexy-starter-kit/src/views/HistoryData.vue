<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="6">
            <b-form-group label="測點" label-for="monitor" label-cols-md="3">
              <v-select
                id="monitor"
                v-model="form.monitors"
                label="desc"
                :reduce="mt => mt._id"
                :options="monitors"
                :close-on-select="false"
                multiple
              />
            </b-form-group>
          </b-col>
          <b-col cols="6">
            <b-form-group
              label="測項"
              label-for="monitorType"
              label-cols-md="3"
            >
              <v-select
                id="monitorType"
                v-model="form.monitorTypes"
                label="desp"
                :reduce="mt => mt._id"
                :options="activatedMonitorTypes"
                :close-on-select="false"
                multiple
              />
              <b-form-checkbox v-model="form.includeRaw"
                >顯示原始值</b-form-checkbox
              >
            </b-form-group>
            <b-button
              class="bg-gradient-success m-50"
              v-for="mtg in monitorTypeGroups"
              :key="mtg._id"
              size="sm"
              @click="form.monitorTypes = mtg.mts"
            >
              {{ mtg.name }}
            </b-button>
          </b-col>
          <b-col cols="6">
            <b-form-group
              label="資料種類"
              label-for="dataType"
              label-cols-md="3"
            >
              <v-select
                id="dataType"
                v-model="form.dataType"
                label="txt"
                :reduce="dt => dt.id"
                :options="dataTypes"
              />
            </b-form-group>
          </b-col>
          <b-col cols="6">
            <b-form-group
              label="資料區間"
              label-for="dataRange"
              label-cols-md="3"
            >
              <date-picker
                id="dataRange"
                v-model="form.range"
                :range="true"
                type="datetime"
                format="YYYY-MM-DD HH:mm"
                value-type="timestamp"
                :show-second="false"
              />
            </b-form-group>
          </b-col>
          <!-- submit and reset -->
          <b-col cols="6" class="text-center">
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              type="submit"
              variant="primary"
              class="mr-1"
              @click="query"
            >
              查詢
            </b-button>
            <b-button
              v-ripple.400="'rgba(186, 191, 199, 0.15)'"
              type="reset"
              class="mr-1"
              variant="outline-secondary"
            >
              取消
            </b-button>
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              type="submit"
              variant="primary"
              class="mr-1"
              @click="downloadExcel"
            >
              下載Excel
            </b-button>
          </b-col>
        </b-row>
      </b-form>
    </b-card>
    <b-card v-show="display" :title="resultTitle">
      <b-table
        striped
        hover
        :fields="columns"
        :items="rows"
        show-empty
        :per-page="15"
        :current-page="currentPage"
        responsive
        sticky-header="800px"
      >
        <template #thead-top>
          <b-tr>
            <b-th></b-th>
            <b-th
              v-for="mt in form.monitorTypes"
              :key="mt"
              :colspan="mtColspan"
              class="text-center"
              style="text-transform: none"
              >{{ getMtDesc(mt) }}</b-th
            >
          </b-tr>
        </template>
      </b-table>
      <b-pagination
        v-model="currentPage"
        :total-rows="rows.length"
        :per-page="15"
        first-text="⏮"
        prev-text="⏪"
        next-text="⏩"
        last-text="⏭"
        class="mt-4"
      ></b-pagination>
    </b-card>
  </div>
</template>
<script lang="ts">
import Vue from 'vue'
import DatePicker from 'vue2-datepicker'
import 'vue2-datepicker/index.css'
import 'vue2-datepicker/locale/zh-tw'
const Ripple = require('vue-ripple-directive')
import { mapState, mapGetters, mapActions, mapMutations } from 'vuex'
import moment from 'moment'
import axios from 'axios'
const excel = require('../libs/excel')
const _ = require('lodash')
import { HistoryTrendParam } from './types'

export default Vue.extend({
  components: {
    DatePicker,
  },
  directives: {
    Ripple,
  },

  data() {
    const range = [
      moment().subtract(1, 'days').minute(0).second(0).millisecond(0).valueOf(),
      moment().add(1, 'hour').minute(0).second(0).millisecond(0).valueOf(),
    ]
    return {
      form: {
        monitors: Array<any>(),
        monitorTypes: Array<any>(),
        dataType: 'hour',
        range,
        includeRaw: false,
      },
      display: false,
      columns: Array<any>(),
      rows: Array<any>(),
      currentPage: 1,
    }
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapState('monitors', ['monitors']),
    ...mapState('monitorTypeGroups', ['monitorTypeGroups']),
    ...mapGetters('monitorTypes', ['mtMap', 'activatedMonitorTypes']),
    ...mapGetters('monitors', ['mMap']),
    ...mapGetters('tables', ['dataTypes']),
    resultTitle(): string {
      return `總共${this.rows.length}筆`
    },
    mtColspan(): number {
      if (this.form.includeRaw) return 2 * this.form.monitors.length

      return this.form.monitors.length
    },
  },
  watch: {},
  async mounted() {
    await this.fetchMonitorTypes()
    await this.fetchMonitors()
    await this.fetchTables()
    await this.fetchMonitorTypeGroups()

    if (this.monitors.length !== 0) {
      this.form.monitors.push(this.monitors[0]._id)
    }

    if (this.activatedMonitorTypes.length !== 0)
      this.form.monitorTypes.push(this.activatedMonitorTypes[0]._id)
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors']),
    ...mapActions('monitorTypeGroups', ['fetchMonitorTypeGroups']),
    ...mapActions('tables', ['fetchTables']),
    ...mapMutations(['setLoading']),
    async query() {
      let diffHour =
        (this.form.range[1] - this.form.range[0]) / (1000 * 60 * 60)
      if (this.form.dataType === 'min' && diffHour > 24 * 31) {
        await this.$bvModal.msgBoxOk('查詢區間不可大於31天')
        return
      }

      this.setLoading({ loading: true })
      this.display = true
      this.rows = []
      this.columns = this.getColumns()
      const monitors = this.form.monitors.join(':')
      const monitorTypes = this.form.monitorTypes.join(':')
      const url = `/HistoryReport/${monitors}/${monitorTypes}/${this.form.dataType}/${this.form.includeRaw}/${this.form.range[0]}/${this.form.range[1]}`
      const ret = await axios.get(url)
      this.setLoading({ loading: false })
      console.info(ret)
      for (const row of ret.data.rows) {
        row.date = moment(row.date).format('lll')
      }

      this.rows = ret.data.rows
    },
    cellDataTd(i: number) {
      return (_value: any, _key: any, item: any) =>
        item.cellData[i].cellClassName
    },
    getMtDesc(mt: string) {
      const mtCase = this.mtMap.get(mt)
      return `${mtCase.desp}(${mtCase.unit})`
    },
    getColumns(): Array<any> {
      const ret = []
      ret.push({
        key: 'date',
        label: '時間',
        stickyColumn: true,
      })
      let i = 0
      for (const mt of this.form.monitorTypes) {
        const mtCase = this.mtMap.get(mt)
        for (const m of this.form.monitors) {
          const mCase = this.mMap.get(m)
          ret.push({
            key: `cellData[${i}].v`,
            label: `${mCase.desc}`,
            tdClass: this.cellDataTd(i),
          })
          i++
          if (this.form.includeRaw) {
            ret.push({
              key: `cellData[${i}].v`,
              label: `${mCase.desc}原始值`,
              tdClass: this.cellDataTd(i),
            })
            i++
          }
        }
      }

      return ret
    },
    async downloadExcel() {
      const baseUrl =
        process.env.NODE_ENV === 'development' ? 'http://localhost:9000/' : '/'
      const monitors = this.form.monitors.join(':')
      let reportUnit = 'Min'
      if (this.form.dataType === 'hour') reportUnit = 'Hour'

      let param: HistoryTrendParam = {
        monitors: this.form.monitors,
        monitorTypes: this.form.monitorTypes,
        raw: this.form.includeRaw,
        tab: this.form.dataType,
        unit: reportUnit,
        filter: 'all',
        start: this.form.range[0],
        end: this.form.range[1],
        output: 'excel',
      }

      const res = await axios.post('/GetHistoryTrend', param, {
        responseType: 'blob',
      })

      const url = window.URL.createObjectURL(new Blob([res.data]))
      const link = document.createElement('a')
      link.href = url
      const fileName = 'data.xlsx'
      link.setAttribute('download', fileName)
      document.body.appendChild(link)
      link.click()
    },
    exportExcel() {
      const title = this.columns.map(e => e.label)
      const key = this.columns.map(e => e.key)
      for (let entry of this.rows) {
        let e = entry as any
        for (let k of key) {
          e[k] = _.get(entry, k)
        }
      }
      const monitorTypes = this.form.monitorTypes.join('')
      const params = {
        title,
        key,
        data: this.rows,
        autoWidth: true,
        filename: `${monitorTypes}歷史資料查詢`,
      }
      excel.export_array_to_excel(params)
    },
  },
})
</script>

<style></style>
