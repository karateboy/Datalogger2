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
                :options="monitorOfNoEPA"
                multiple
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
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
import Vue from 'vue';
import vSelect from 'vue-select';
import DatePicker from 'vue2-datepicker';
import 'vue2-datepicker/index.css';
import 'vue2-datepicker/locale/zh-tw';
const Ripple = require('vue-ripple-directive');
import { mapState, mapActions, mapGetters, mapMutations } from 'vuex';
import moment from 'moment';
import axios from 'axios';

export default Vue.extend({
  components: {
    DatePicker,
    vSelect,
  },
  directives: {
    Ripple,
  },
  data() {
    let monitors = Array<string>();
    const range = [moment().subtract(1, 'days').valueOf(), moment().valueOf()];
    let me: any = this;
    return {
      display: false,
      alarmLevels: [
        { id: 1, txt: '資訊' },
        { id: 2, txt: '警告' },
        { id: 3, txt: '嚴重' },
      ],
      columns: [
        {
          key: 'monitor',
          label: '測站',
          sortable: true,
          formatter: (m: string) => me.mMap.get(m).desc,
        },
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
          key: 'desc',
          label: '詳細資訊',
          sortable: true,
        },
      ],
      rows: [],
      form: {
        monitors,
        range,
        alarmLevel: 1,
      },
    };
  },
  computed: {
    ...mapGetters('monitors', ['mMap', 'monitorOfNoEPA']),
    ...mapGetters('monitorTypes', ['mtMap']),
  },
  async mounted() {
    await this.fetchMonitors();
    await this.fetchMonitorTypes();
    if (this.monitorOfNoEPA.length !== 0)
      this.form.monitors.push(this.monitorOfNoEPA[0]._id);
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors']),
    ...mapMutations(['setLoading']),
    async query() {
      this.display = true;
      let monitors = this.form.monitors.join(':');
      const url = `/MonitorAlarmReport/${monitors}/${this.form.alarmLevel}/${this.form.range[0]}/${this.form.range[1]}`;
      console.info('url', url);
      try {
        const res = await axios.get(url);
        if (res.status === 200) this.rows = res.data;
      } catch (err) {
        console.error(`${err}`);
      }
    },
    rowClass(item: any, type: any) {
      if (!item || type !== 'row') return;
      switch (item.level) {
        case 1:
          return 'table-success';

        case 2:
          return 'table-warning';

        case 3:
          return 'table-danger';
      }
    },
    selectAllMonitors() {
      this.form.monitors = [];
      for (let m of this.monitorOfNoEPA) this.form.monitors.push(m._id);
    },
  },
});
</script>

<style></style>
