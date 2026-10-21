<template>
  <div>
    <b-card no-body>
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="12">
            <b-form-group label="測點" label-for="monitor" label-cols-md="3">
              <v-select
                id="monitor"
                v-model="form.monitors"
                label="desc"
                :reduce="mt => mt._id"
                :options="qaMonitors"
                :close-on-select="false"
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
                v-model="form.dataType"
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
          <b-col offset-md="3">
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              variant="primary"
              class="mr-1"
              @click="qaHour"
            >
              QA品保重新計算
            </b-button>
          </b-col>
        </b-row>
      </b-form>
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
import { mapState, mapGetters, mapActions } from 'vuex'
import moment from 'moment'
import axios from 'axios'
import { Monitor } from '@/store/monitors/types'

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
      moment().subtract(1, 'hour').minute(0).second(0).millisecond(0).valueOf(),
      moment().minute(0).second(0).millisecond(0).valueOf(),
    ]
    let cdxStartTime = 0
    return {
      dataTypes: [{ txt: '小時資料', id: 'hour' }],
      form: {
        monitors: Array<any>(),
        monitorTypes: [],
        dataType: 'hour',
        range,
        cdxStartTime,
      },
    }
  },
  computed: {
    ...mapState('monitors', ['monitors']),
    ...mapGetters('monitors', ['mMap']),
    qaMonitors(): Array<Monitor> {
      return this.monitors.filter(monitor => monitor._id.startsWith('QA'))
    },
  },
  async mounted() {
    await this.fetchMonitors()

    for (const m of this.qaMonitors) this.form.monitors.push(m._id)
  },
  methods: {
    ...mapActions('monitors', ['fetchMonitors']),
    async qaHour() {
      const monitors = this.form.monitors.join(':')
      const url = `/QA/${monitors}/${this.form.range[0]}/${this.form.range[1]}`

      try {
        const res = await axios.get(url)
        if (res.data.ok) {
          this.$bvModal.msgBoxOk('開始品保計算')
        }
      } catch (err) {
        throw new Error('failed to QA hour')
      }
    },
  },
})
</script>

<style></style>
