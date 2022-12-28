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
    <b-card v-show="displayCdx">
      <b-table striped hover :fields="columns" :items="rows" />
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
      moment().subtract(1, 'hour').minute(0).second(0).millisecond(0).valueOf(),
      moment().minute(0).second(0).millisecond(0).valueOf(),
    ];
    let displayCdx = false;
    let cdxStartTime = 0;
    return {
      dataTypes: [{ txt: '小時資料', id: 'hour' }],
      form: {
        monitors: Array<any>(),
        monitorTypes: [],
        dataType: 'hour',
        range,
        cdxStartTime,
      },
      displayCdx,
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
                return '資訊';

              case 2:
                return '警告';

              case 3:
                return '錯誤';
            }
          },
        },
        {
          key: 'src',
          label: '來源',
          sortable: true,
          formatter: (src: string) => {
            let tokens = src.split(':');
            switch (tokens[0]) {
              case 'I':
                return `設備:${tokens[1]}`;

              case 'T':
                return `測項:${tokens[1]}`;

              case 'S':
                if (tokens[1] === 'System') return `系統`;
                else return `系統:${tokens[1]}`;
              default:
                return src;
            }
          },
        },
        {
          key: 'info',
          label: '詳細資訊',
          sortable: true,
        },
      ],
      rows: [],
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
        this.form.cdxStartTime = new Date().getTime();
        const res = await axios.get(url);
        if (res.status === 200) {
          this.$bvModal.msgBoxOk('Cdx重傳資料中');
          this.displayCdx = true;
          this.getCdxUploadEvents();
        }
      } catch (err) {
        throw new Error('failed to upload data');
      }
    },
    async getCdxUploadEvents() {
      try {
        let src = 'S:CDX';
        let res = await axios.get(
          `/Alarms/${src}/1/${this.form.cdxStartTime}/${
            this.form.cdxStartTime + 1000 * 60 * 15
          }`,
        );
        if (res.status === 200) {
          this.rows = res.data;
        }
      } catch (err) {
        throw new Error(`$err`);
      }
    },
  },
});
</script>

<style></style>
