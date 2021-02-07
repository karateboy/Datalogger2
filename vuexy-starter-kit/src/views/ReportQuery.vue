<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="12">
            <b-form-group
              label="報表種類"
              label-for="reportType"
              label-cols-md="3"
            >
              <v-select
                id="reportType"
                v-model="form.reportType"
                label="txt"
                :reduce="dt => dt.id"
                :options="reportTypes"
              />
            </b-form-group>
          </b-col>
        </b-row>
        <b-row>
          <b-col cols="12">
            <b-form-group
              label="查詢日期"
              label-for="dataRange"
              label-cols-md="3"
            >
              <date-picker
                id="dataRange"
                v-model="form.date"
                :type="pickerType"
                value-type="timestamp"
                :show-second="false"
              />
            </b-form-group>
          </b-col>
        </b-row>
        <b-row>
          <!-- submit and reset -->
          <b-col offset-md="3">
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
              variant="outline-secondary"
            >
              取消
            </b-button>
          </b-col>
        </b-row>
      </b-form>
    </b-card>
    <b-card v-show="display">
      <b-table striped hover :fields="columns" :items="rows" />
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
import Ripple from 'vue-ripple-directive'
import moment from 'moment'
import axios from 'axios'

export default Vue.extend({
  components: {
    DatePicker,
    vSelect,
  },
  directives: {
    Ripple,
  },
  data() {
    const date = moment().valueOf()
    return {
      display: false,
      reportTypes: [
        { id: 'daily', txt: '日報' },
        { id: 'monthly', txt: '月報' },
      ],
      columns: [
        {
          key: 'time',
          label: '時間',
          sortable: true,
        },
        {
          key: 'level',
          label: '等級',
          sortable: true,
        },
        {
          key: 'src',
          label: '來源',
          sortable: true,
        },
        {
          key: 'info',
          label: '詳細資訊',
          sortable: true,
        },
      ],
      rows: [],
      form: {
        date,
        reportType: 'daily',
      },
    }
  },
  computed: {
    pickerType() {
      if (this.form.reportType === 'daily') return 'date'
      return 'month'
    },
  },
  methods: {
    async query() {
      this.display = true
      const url = `/monitorReport/${this.form.reportType}/${this.form.date}`
      const res = await axios.get(url)
      const ret = res.data
      console.log(ret)
    },
  },
})
</script>

<style></style>
