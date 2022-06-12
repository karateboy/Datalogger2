<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="12">
            <b-form-group label="測點" label-for="monitor" label-cols-md="3">
              <v-select
                id="monitor"
                v-model="form.monitors"
                label="desc"
                :reduce="mt => mt._id"
                :options="monitors"
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
                format="YYYY-MM-DD"
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
              @click="recalculate"
            >
              重新計算
            </b-button>
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              variant="primary"
              class="mr-1"
              @click="upload"
            >
              重新上傳
            </b-button>
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              variant="primary"
              class="mr-1"
              @click="cdxUpload"
            >
              CDX上傳
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
import Vue from 'vue';
import vSelect from 'vue-select';
import DatePicker from 'vue2-datepicker';
import 'vue2-datepicker/index.css';
import 'vue2-datepicker/locale/zh-tw';
const Ripple = require('vue-ripple-directive');
import { mapState, mapGetters, mapActions } from 'vuex';
import moment from 'moment';
import axios from 'axios';

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
      moment().subtract(1, 'days').hour(0).minute(0).millisecond(0).valueOf(),
      moment().hour(23).minute(59).minute(0).millisecond(0).valueOf(),
    ];
    return {
      dataTypes: [
        { txt: '小時資料', id: 'hour' },
        // { txt: '分鐘資料', id: 'min' },
        // { txt: '秒資料', id: 'second' },
      ],
      form: {
        monitors: Array<any>(),
        monitorTypes: [],
        dataType: 'hour',
        range,
      },
    };
  },
  computed: {
    ...mapState('monitors', ['monitors']),
    ...mapGetters('monitors', ['mMap']),
  },
  async mounted() {
    await this.fetchMonitors();

    for (const m of this.monitors) this.form.monitors.push(m._id);
  },
  methods: {
    ...mapActions('monitors', ['fetchMonitors']),
    async recalculate() {
      const monitors = this.form.monitors.join(':');
      const url = `/Recalculate/${monitors}/${this.form.range[0]}/${this.form.range[1]}`;

      try {
        const res = await axios.get(url);
        if (res.data.ok) {
          this.$bvModal.msgBoxOk('開始重新計算小時值');
        }
      } catch (err) {
        throw new Error('failed to recalculate hour');
      }
    },
    async upload() {
      const monitors = this.form.monitors.join(':');
      const url = `/Upload/${this.form.range[0]}/${this.form.range[1]}`;

      try {
        const res = await axios.get(url);
        if (res.data.ok) {
          this.$bvModal.msgBoxOk('重新上傳資料');
        }
      } catch (err) {
        throw new Error('failed to upload data');
      }
    },
    async cdxUpload() {
      const url = `/CdxUpload/${this.form.range[0]}/${this.form.range[1]}`;

      try {
        const res = await axios.get(url);
        if (res.status === 200) {
          this.$bvModal.msgBoxOk('Cdx重傳資料  上傳結果請查詢警報');
        }
      } catch (err) {
        throw new Error('failed to upload data');
      }
    },
  },
});
</script>

<style></style>
