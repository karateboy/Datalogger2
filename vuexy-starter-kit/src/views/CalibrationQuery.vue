<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
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
import Vue from 'vue';
import DatePicker from 'vue2-datepicker';
import 'vue2-datepicker/index.css';
import 'vue2-datepicker/locale/zh-tw';
const Ripple = require('vue-ripple-directive');
import moment from 'moment';
import axios from 'axios';

export default Vue.extend({
  components: {
    DatePicker,
  },
  directives: {
    Ripple,
  },

  data() {
    const range = [moment().subtract(1, 'days').valueOf(), moment().valueOf()];
    return {
      display: false,
      columns: [
        {
          key: 'monitorType',
          label: '測項',
          sortable: true,
        },
        {
          key: 'startTime',
          label: '開始時間',
          sortable: true,
        },
        {
          key: 'endTime',
          label: '結束時間',
          sortable: true,
        },
        {
          key: 'zero_val',
          label: '零點讀值',
          sortable: true,
        },
        {
          key: 'span_val',
          label: '全幅讀值',
          sortable: true,
        },
        {
          key: 'span_std',
          label: '全幅標準值',
          sortable: true,
        },
      ],
      rows: [],
      form: {
        range,
      },
    };
  },
  methods: {
    async query() {
      this.display = true;
      const url = `/CalibrationReport/${this.form.range[0]}/${this.form.range[1]}`;
      const res = await axios.get(url);
      const ret = res.data;
      this.rows = ret;
    },
  },
});
</script>

<style></style>
