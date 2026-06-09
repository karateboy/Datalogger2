<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="6">
            <b-form-group
              :label="$t('monitorType')"
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
            </b-form-group>
          </b-col>
          <b-col cols="6">
            <b-form-group
              :label="$t('dataType')"
              label-for="dataType"
              label-cols-md="3"
            >
              <v-select
                id="dataType"
                v-model="form.dataType"
                label="txt"
                :reduce="dt => dt.id"
                :options="translateDataTypes"
              />
            </b-form-group>
          </b-col>
          <b-col cols="6">
            <b-form-group
              :label="$t('timeUnit')"
              label-for="reportUnit"
              label-cols-md="3"
            >
              <v-select
                id="reportUnit"
                v-model="form.reportUnit"
                label="txt"
                :reduce="dt => dt.id"
                :options="reportUnits"
              />
            </b-form-group>
          </b-col>
          <b-col cols="6">
            <b-form-group
              :label="$t('dataRange')"
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
          <b-col class="text-center">
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              type="submit"
              variant="primary"
              class="mr-1"
              @click="query"
            >
              {{ $t('query') }}
            </b-button>
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              type="submit"
              variant="primary"
              class="mr-1"
              @click="downloadExcel"
            >
              {{ $t('downloadExcel') }}
            </b-button>
          </b-col>
        </b-row>
      </b-form>
    </b-card>
    <b-card v-show="display">
      <b-card-body><div id="chart_container" /></b-card-body>
    </b-card>
  </div>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<style>
.highcharts-container,
.highcharts-container svg {
  width: 100% !important;
}
</style>
<script lang="ts">
import Vue from 'vue'
import DatePicker from 'vue2-datepicker'
import 'vue2-datepicker/index.css'
import 'vue2-datepicker/locale/zh-tw'
const Ripple = require('vue-ripple-directive')
import { mapState, mapActions, mapMutations, mapGetters } from 'vuex'
import darkTheme from 'highcharts/themes/dark-unica'
import useAppConfig from '../@core/app-config/useAppConfig'
import moment from 'moment'
import axios from 'axios'
import highcharts from 'highcharts'
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
      moment().minute(0).second(0).millisecond(0).valueOf(),
    ]
    let includeRaw = false
    return {
      statusFilters: [
        { id: 'all', txt: '全部' },
        { id: 'normal', txt: '正常量測值' },
        { id: 'calibration', txt: '校正' },
        { id: 'maintenance', txt: '維修' },
        { id: 'invalid', txt: '無效數據' },
        { id: 'valid', txt: '有效數據' },
      ],
      reportUnit: 'Hour',
      display: false,
      chartTypes: [
        {
          type: 'line',
          desc: '折線圖',
        },
        {
          type: 'spline',
          desc: '曲線圖',
        },
        {
          type: 'area',
          desc: '面積圖',
        },
        {
          type: 'areaspline',
          desc: '曲線面積圖',
        },
        {
          type: 'column',
          desc: '柱狀圖',
        },
        {
          type: 'scatter',
          desc: '點圖',
        },
      ],
      form: {
        monitors: Array<string>(),
        monitorTypes: Array<string>(),
        dataType: 'hour',
        reportUnit: 'Hour',
        statusFilter: 'all',
        chartType: 'line',
        range,
        includeRaw,
        YMax: undefined,
        YMin: 0,
      },
    }
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapState('monitorTypeGroups', ['monitorTypeGroups']),
    ...mapGetters('monitorTypes', ['activatedMonitorTypes']),
    ...mapState('monitors', ['monitors']),
    ...mapGetters('tables', ['dataTypes']),
    reportUnits(): Array<any> {
      const minReportTypes = [
        { txt: 'Min', id: 'Min' },
        { txt: '5 Min', id: 'FiveMin' },
        { txt: '6 Min', id: 'SixMin' },
        { txt: '10 Min', id: 'TenMin' },
        { txt: '15 Min', id: 'FifteenMin' },
      ]
      const HourReportTypes = [
        { txt: 'Hour', id: 'Hour' },
        { txt: 'Day', id: 'Day' },
        { txt: 'Month', id: 'Month' },
      ]

      return this.form.reportUnit === 'Hour' ? HourReportTypes : minReportTypes
    },
    translateDataTypes(): Array<any> {
      return [
        { id: 'hour', txt: this.$i18n.t('hourData') },
        { id: 'min', txt: this.$i18n.t('minData') },
      ]
    },
  },
  watch: {
    'form.dataType': function (val, oldVal) {
      if (val === 'hour') {
        this.form.reportUnit = 'Hour'
      } else {
        this.form.reportUnit = 'Min'
      }
    },
  },
  async mounted() {
    const { skin } = useAppConfig()
    if (skin.value == 'dark') {
      darkTheme(highcharts)
    }

    await this.fetchMonitorTypes()
    await this.fetchMonitors()
    await this.fetchTables()
    await this.fetchMonitorTypeGroups()

    if (this.activatedMonitorTypes.length !== 0)
      this.form.monitorTypes.push(this.activatedMonitorTypes[0]._id)

    if (this.monitors.length !== 0) {
      this.form.monitors.push(this.monitors[0]._id)
    }
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

      let param: HistoryTrendParam = {
        monitors: this.form.monitors,
        monitorTypes: this.form.monitorTypes,
        raw: this.form.includeRaw,
        tab: this.form.dataType,
        unit: this.form.reportUnit,
        filter: this.form.statusFilter,
        start: this.form.range[0],
        end: this.form.range[1],
        output: 'html',
      }

      const res = await axios.post('/GetHistoryTrend', param)
      const ret = res.data

      this.setLoading({ loading: false })
      ret.title!.text = ""
      if (this.form.chartType !== 'boxplot') {
        ret.chart = {
          type: this.form.chartType,
          zoomType: 'x',
          panning: true,
          panKey: 'shift',
          alignTicks: false,
        }

        const pointFormatter = function pointFormatter(this: any) {
          const d = new Date(this.x)
          return `${d.toLocaleString()}:${Math.round(this.y)}`
        }

        ret.colors = [
          '#7CB5EC',
          '#434348',
          '#90ED7D',
          '#F7A35C',
          '#8085E9',
          '#F15C80',
          '#E4D354',
          '#2B908F',
          '#FB9FA8',
          '#91E8E1',
          '#7CB5EC',
          '#80C535',
          '#969696',
        ]

        ret.tooltip = { valueDecimals: 2 }
        ret.legend = { enabled: true }
        ret.credits = {
          enabled: false,
          href: 'http://www.wecc.com.tw/',
        }
        ret.xAxis.type = 'datetime'

        let yAxisArray = ret.yAxis as Array<highcharts.YAxisOptions>
        for (let yAxis of yAxisArray) {
          if (typeof this.form.YMax === 'number') yAxis.max = this.form.YMax

          if (typeof this.form.YMin === 'number') yAxis.min = this.form.YMin

          yAxis.gridLineColor = '#666666'
          yAxis.lineColor = '#000000'
          yAxis.tickColor = '#000000'
          yAxis.labels = {
            style: {
              color: '#000000',
              fontSize: '1rem',
            },
          }
        }
        ret.plotOptions = {
          scatter: {
            tooltip: {
              pointFormatter,
            },
          },
        }
        ret.time = {
          timezoneOffset: -420,
        }
      }
      highcharts.chart('chart_container', ret)
    },
    async downloadExcel() {
      let param: HistoryTrendParam = {
        monitors: this.form.monitors,
        monitorTypes: this.form.monitorTypes,
        raw: this.form.includeRaw,
        tab: this.form.dataType,
        unit: this.form.reportUnit,
        filter: this.form.statusFilter,
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
      const fileName = 'chat.xlsx'
      link.setAttribute('download', fileName)
      document.body.appendChild(link)
      link.click()
    },
  },
})
</script>

<style></style>
