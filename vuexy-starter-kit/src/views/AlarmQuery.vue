<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="6">
            <b-form-group
              :label="$t('AlarmLevel')"
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
          <b-col cols="6" class="text-center">
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              type="submit"
              variant="primary"
              class="mr-1"
              @click="query"
            >
              {{ $t('query') }}
            </b-button>
          </b-col>
        </b-row>
      </b-form>
    </b-card>
    <b-card v-show="display">
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
        { id: 1, txt: 'Info' },
        { id: 2, txt: 'Warning' },
        { id: 3, txt: 'Error' },
      ],
      rows: [],
      form: {
        range,
        alarmLevel: 1,
      },
    }
  },
  computed: {
    columns(): Array<any> {
      return [
        {
          key: 'time',
          label: this.$i18n.t('time'),
          sortable: true,
          formatter: (v: number) => moment(v).format('lll'),
        },
        {
          key: 'level',
          label: this.$i18n.t('level'),
          sortable: true,
          formatter: (v: number) => {
            switch (v) {
              case 1:
                return 'Info'

              case 2:
                return 'Warning'

              case 3:
                return 'Error'
            }
          },
        },
        {
          key: 'desc',
          label: this.$i18n.t('details'),
          sortable: true,
        },
      ]
    },
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
  },
})
</script>

<style></style>
