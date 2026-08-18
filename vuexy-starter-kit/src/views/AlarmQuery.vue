<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="6">
            <b-form-group
              label="警報等級"
              label-for="alarmLevel"
              label-cols-md="3"
            >
              <v-select
                id="alarmLevel"
                v-model="form.alarmLevel"
                label="txt"
                :reduce="dt => dt.id"
                :options="alarmLevels"
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
          </b-col>
        </b-row>
      </b-form>
    </b-card>
    <b-card v-show="display">
      <b-button variant="info" @click="exportExcel"> 匯出Excel </b-button>
      <b-table
        striped
        hover
        :fields="columns"
        :items="rows"
        :tbody-tr-class="rowClass"
      />
    </b-card>
  </div>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script lang="ts">
import Vue from 'vue'
import vSelect from 'vue-select'
import DatePicker from 'vue2-datepicker'
import 'vue2-datepicker/index.css'
import 'vue2-datepicker/locale/zh-tw'
const Ripple = require('vue-ripple-directive')
import moment from 'moment'
import axios from 'axios'
import { mapMutations } from 'vuex'
import _ from 'lodash'
import excel from '@/libs/excel'

export default Vue.extend({
  components: {
    DatePicker,
    vSelect,
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
      display: false,
      alarmLevels: [
        { id: 1, txt: '資訊' },
        { id: 2, txt: '警告' },
        { id: 3, txt: '錯誤' },
      ],
      columns: [
        {
          key: 'time',
          label: '時間',
          sortable: true,
          formatter: (v: number) => moment(v).format('lll'),
        },
        {
          key: 'level',
          label: '等級',
          sortable: true,
          formatter: (v: number) => {
            switch (v) {
              case 1:
                return '資訊'

              case 2:
                return '警告'

              case 3:
                return '錯誤'
            }
          },
        },
        {
          key: 'src',
          label: '來源',
          sortable: true,
          formatter: (src: string) => {
            let tokens = src.split(':')
            switch (tokens[0]) {
              case 'I':
                return `設備:${tokens[1]}`

              case 'T':
                return `測項:${tokens[1]}`

              case 'S':
                if (tokens[1] === 'System') return `系統`
                else return `系統:${tokens[1]}`
              default:
                return src
            }
          },
        },
        {
          key: 'desc',
          label: '詳細資訊',
          sortable: true,
        },
      ],
      rows: [],
      form: {
        range,
        alarmLevel: 1,
      },
    }
  },
  methods: {
    ...mapMutations(['setLoading']),
    async query() {
      try {
        this.setLoading({ loading: true })
        const url = `/AlarmReport/${this.form.alarmLevel}/${this.form.range[0]}/${this.form.range[1]}`
        const res = await axios.get(url)
        this.display = true
        const ret = res.data
        this.rows = ret
      } catch (err) {
        console.error(`${err}`)
      } finally {
        this.setLoading({ loading: false })
      }
    },
    rowClass(item: any, type: any) {
      if (!item || type !== 'row') return
      switch (item.level) {
        case 1:
          return 'table-success'

        case 2:
          return 'table-warning'

        case 3:
          return 'table-danger'
      }
    },
    exportExcel() {
      const title = this.columns.map(e => e.label)
      const key = this.columns.map(e => e.key)
      const key_formatter = this.columns.map(e => ({
        key: e.key,
        formatter: e.formatter,
      }))

      let rows = Array<any>();
      for (let entry of this.rows) {
        let e = entry as any
        let e2 = _.cloneDeep(e)
        rows.push(e2)
        for (let k of key_formatter) {
          e2[k.key] = k.formatter
            ? k.formatter(entry[k.key])
            : _.get(entry, k.key)
        }
      }

      const params = {
        title,
        key,
        data: rows,
        autoWidth: true,
        filename: `警報資料`,
      }
      excel.export_array_to_excel(params)
    },
  },
})
</script>

<style></style>
