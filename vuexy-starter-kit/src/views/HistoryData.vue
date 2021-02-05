<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="12">
            <b-form-group
              label="測項"
              label-for="monitorType"
              label-cols-md="3"
            >
              <v-select
                id="monitorType"
                v-model="selectedMonitorTypes"
                label="desp"
                :reduce="mt => mt._id"
                :options="monitorTypes"
                multiple
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group
              label="資料來源"
              label-for="dataType"
              label-cols-md="3"
            >
              <v-select
                id="dataType"
                v-model="dataType"
                label="txt"
                :reduce="dt => dt.id"
                :options="dataTypes"
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group
              label="資料區間"
              label-for="dataRange"
              label-cols-md="3"
            >
              <date-picker
                id="dataRange"
                v-model="range"
                :range="true"
                type="datetime"
                format="YYYY-MM-DD HH:mm"
                value-type="timestamp"
                :show-second="false"
              />
            </b-form-group>
          </b-col>
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
    <b-card v-show="queried">
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
import { mapState, mapActions } from 'vuex'
import moment from 'moment'
import axios from 'axios'

export default Vue.extend({
  components: {
    vSelect,
    DatePicker,
  },
  directives: {
    Ripple,
  },

  data() {
    const range = [
      moment()
        .subtract(1, 'days')
        .valueOf(),
      moment().valueOf(),
    ]
    return {
      selectedMonitorTypes: [],
      dataTypes: [
        { txt: '小時資料', id: 'hour' },
        { txt: '分鐘資料', id: 'min' },
        { txt: '秒資料', id: 'second' },
      ],
      dataType: 'hour',
      range,
      queried: false,
      rows: [],
    }
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    columns() {
      const ret = []
      ret.push({
        key: 'date',
        label: '時間',
      })
      for (let i = 0; i < this.selectedMonitorTypes.length; i += 1) {
        const mtCase = this.monitorTypes.find(
          // eslint-disable-next-line no-underscore-dangle
          mt => mt._id === this.selectedMonitorTypes[i]
        )
        ret.push({
          key: `cellData[${i}].v`,
          label: `${mtCase.desp}(${mtCase.unit})`,
        })
      }
      return ret
    },
  },
  mounted() {
    this.fetchMonitorTypes().then(() => {
      if (this.monitorTypes.length !== 0) {
        this.selectedMonitorTypes.splice(0, this.selectedMonitorTypes.length)
        // eslint-disable-next-line no-underscore-dangle
        this.selectedMonitorTypes.push(this.monitorTypes[0]._id)
      }
    })
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    async query() {
      this.queried = true
      const url = `/HistoryReport/${this.selectedMonitorTypes.join(':')}/${
        this.dataType
      }/${this.range[0]}/${this.range[1]}`
      const ret = await axios.get(url)
      this.rows = ret.data.rows
      for (const row of this.rows) {
        row.date = moment(row.date).format("lll")
      }
    },
  },
})
</script>

<style></style>
