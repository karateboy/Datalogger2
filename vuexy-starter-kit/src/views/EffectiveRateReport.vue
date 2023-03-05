<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="12">
            <b-form-group
              label="查詢區間"
              label-for="dataRange"
              label-cols-md="3"
            >
              <date-picker
                id="dataRange"
                v-model="form.range"
                :range="true"
                type="date"
                format="YYYY-MM-DD"
                value-type="timestamp"
                :show-second="false"
              />
              <b-button
                variant="gradient-primary"
                class="ml-1"
                size="md"
                @click="setToday"
                >今天</b-button
              >
              <b-button
                variant="gradient-primary"
                class="ml-1"
                size="md"
                @click="setLast2Days"
                >前兩天</b-button
              >
              <b-button
                variant="gradient-primary"
                class="ml-1"
                size="md"
                @click="set3DayBefore"
                >前三天</b-button
              >
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
              class="mr-1"
              variant="outline-secondary"
            >
              取消
            </b-button>
            <b-button variant="outline-success" size="sm" @click="exportExcel"
              ><b-img
                v-b-tooltip.hover
                src="../assets/excel_export.svg"
                title="匯出 Excel"
                width="24"
                fluid
            /></b-button>
          </b-col>
        </b-row>
      </b-form>
    </b-card>
    <b-card v-show="display">
      <b-table striped hover :fields="fields" :items="errorSensorList">
      </b-table>
    </b-card>
  </div>
</template>
<script lang="ts">
import Vue from 'vue';
import DatePicker from 'vue2-datepicker';
import 'vue2-datepicker/index.css';
import 'vue2-datepicker/locale/zh-tw';
const Ripple = require('vue-ripple-directive');
import moment from 'moment';
import axios from 'axios';
import { mapActions, mapState, mapGetters, mapMutations } from 'vuex';

const excel = require('../libs/excel');
const _ = require('lodash');

interface Sensor {
  _id: string;
  date: number;
  status: string;
  effectRate?: number;
  desc: string;
}

interface EffectiveRate {
  _id: string;
  rate: number;
}

interface ErrorAction {
  sensorID: string;
  errorType: string;
  action: string;
}

interface ErrorReport {
  _id: number;
  noErrorCode: Array<string>;
  powerError: Array<string>;
  disconnect: Array<string>;
  constant: Array<string>;
  ineffective: Array<EffectiveRate>;
  inspections: Array<ErrorAction>;
  actions: Array<ErrorAction>;
}

interface Field {
  key: string;
  label: string;
  sortable: boolean;
  formatter?: any;
}

export default Vue.extend({
  components: {
    DatePicker,
  },
  directives: {
    Ripple,
  },
  data() {
    let range = [
      moment().subtract(6, 'day').startOf('day').valueOf(),
      moment().startOf('day').valueOf(),
    ];

    const errorStatus = Array<string>('lt95');
    return {
      display: false,
      errorReports: Array<ErrorReport>(),
      items: [],
      timer: 0,
      errorStatus,
      form: {
        range,
      },
    };
  },
  computed: {
    ...mapState('monitors', ['monitors']),
    ...mapGetters('monitors', ['mMap']),

    fields() {
      let ret: Array<Field> = [
        {
          key: 'date',
          label: '檢核日期',
          formatter: (date: number) => {
            return moment(date).format('ll');
          },
          sortable: true,
        },
        {
          key: '_id',
          label: '設備序號',
          sortable: true,
        },
        {
          key: 'desc',
          label: '設備名稱',
          sortable: true,
        },
        {
          key: 'status',
          label: '狀態',
          sortable: true,
        },
      ];

      if (this.errorStatus.indexOf('lt95') !== -1) {
        ret.push({
          key: 'effectRate',
          label: '完整率',
          sortable: true,
          formatter: (v: number) => {
            if (v === null) {
              return `N/A`;
            } else {
              let percent = v * 100;
              return `${percent.toFixed(0)}%`;
            }
          },
        });
      }

      return ret;
    },
    errorSensorList(): Array<Sensor> {
      let ret = Array<Sensor>();
      for (let errorReport of this.errorReports) {
        let sensors = this.getErrorSensorList(errorReport);
        ret = ret.concat(sensors);
      }

      return ret;
    },
  },
  async mounted() {
    await this.fetchMonitors();
  },
  methods: {
    ...mapActions('monitors', ['fetchMonitors']),
    ...mapMutations(['setLoading']),
    setToday() {
      this.form.range = [moment().startOf('day').valueOf(), moment().valueOf()];
    },
    setLast2Days() {
      const last2days = moment().subtract(2, 'day');
      this.form.range = [
        last2days.startOf('day').valueOf(),
        moment().startOf('day').valueOf(),
      ];
    },
    set3DayBefore() {
      const threeDayBefore = moment().subtract(3, 'day');
      this.form.range = [
        threeDayBefore.startOf('day').valueOf(),
        moment().startOf('day').valueOf(),
      ];
    },
    async query() {
      this.display = true;
      this.setLoading({ loading: true });
      try {
        await this.getErrorReportList();
      } catch (err) {
        throw err;
      } finally {
        this.setLoading({ loading: false });
      }
    },
    async getErrorReportList(): Promise<void> {
      try {
        const ret = await axios.get(
          `/ErrorReport/${this.form.range[0]}/${this.form.range[1]}`,
        );
        this.errorReports = ret.data as Array<ErrorReport>;
      } catch (err) {
        console.error(`${err}`);
      }
    },
    getErrorSensorList(errorReport: ErrorReport): Array<Sensor> {
      let date = errorReport._id;
      let ret = Array<Sensor>();
      let updateMap = (
        actionList: Array<ErrorAction>,
        map: Map<string, Map<string, string>>,
      ) => {
        for (let action of actionList) {
          if (!map.has(action.errorType))
            map.set(action.errorType, new Map<string, string>());

          let errorMap = map.get(action.errorType) as Map<string, string>;
          errorMap.set(action.sensorID, action.action);
        }
      };

      let inspectionMap = new Map<string, Map<string, string>>();
      updateMap(errorReport.inspections, inspectionMap);
      let actionMap = new Map<string, Map<string, string>>();
      updateMap(errorReport.actions, actionMap);
      if (this.errorStatus.indexOf('lt95') !== -1) {
        for (const ef of errorReport.ineffective) {
          let sensor = this.populateSensor(date, ef._id, '完整率異常');
          if (sensor !== null) {
            sensor.effectRate = ef.rate;
            ret.push(sensor as Sensor);
          }
        }
      }

      return ret;
    },
    populateSensor(date: number, id: string, status: string): Sensor | null {
      const m = this.mMap.get(id);
      if (m === undefined) {
        console.error([id, m]);
        return null;
      }

      let sensor = Object.assign({}, m) as Sensor;

      sensor.status = status;
      sensor.date = date;
      sensor.desc = m.desc;
      return sensor;
    },
    exportExcel() {
      const title = this.fields.map(e => e.label);
      const key = this.fields.map(e => e.key);
      const data = Array<any>();
      for (let entry of this.errorSensorList) {
        let e: any = Object.assign({}, entry);
        for (let field of this.fields) {
          let k = field.key;
          if (field.formatter) {
            e[k] = field.formatter(_.get(entry, k));
          } else e[k] = _.get(entry, k);
        }
        data.push(e);
      }
      const start = new Date(this.form.range[0]);
      start.toLocaleDateString();
      let month = String(start.getMonth() + 1).padStart(2, '0');
      let day = String(start.getDate()).padStart(2, '0');
      const end = new Date(this.form.range[1]);
      let monthEnd = String(end.getMonth() + 1).padStart(2, '0');
      let dayEnd = String(end.getDate()).padStart(2, '0');
      const params = {
        title,
        key,
        data,
        autoWidth: true,
        filename: `${start.getFullYear()}${month}${day}_${monthEnd}${dayEnd}完整率異常列表`,
      };
      excel.export_array_to_excel(params);
    },
  },
});
</script>
<style></style>
