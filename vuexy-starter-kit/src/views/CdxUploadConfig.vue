<template>
  <b-card no-body>
    <b-tabs card>
      <b-tab title="上傳歷史紀錄" active>
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
                  v-model="range"
                  :range="true"
                  type="datetime"
                  format="YYYY-MM-DD HH:mm"
                  value-type="timestamp"
                  :show-second="false"
                />
                <b-button
                  v-ripple.400="'rgba(255, 255, 255, 0.15)'"
                  type="submit"
                  variant="primary"
                  class="ml-2"
                  @click="getCdxUploadEvents"
                >
                  查詢
                </b-button>
              </b-form-group>
            </b-col>
          </b-row>
        </b-form>

        <b-table
          striped
          hover
          :fields="cdxUploadColumns"
          :items="cdxUploadLogs"
        />
      </b-tab>
      <b-tab title="CDX上傳設定">
        <b-table :fields="configColumns" :items="[cdxConfig]">
          <template #cell(enable)="row">
            <b-form-checkbox v-model="row.item.enable" />
          </template>
          <template #cell(user)="row">
            <b-form-input v-model="row.item.user" />
          </template>
          <template #cell(password)="row">
            <b-form-input v-model="row.item.password" />
          </template>
          <template #cell(siteCounty)="row">
            <b-form-input v-model="row.item.siteCounty" />
          </template>
          <template #cell(siteID)="row">
            <b-form-input v-model="row.item.siteID" />
          </template>
        </b-table>
        <b-row class="text-center">
          <b-col>
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              variant="primary"
              class="mr-1"
              @click="saveCdxConfig"
            >
              儲存
            </b-button>
          </b-col>
        </b-row>
      </b-tab>
      <b-tab title="CDX上傳測項設定" class="pt-0">
        <b-row class="pb-2">
          <b-col class="text-center">
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              variant="primary"
              class="mr-1"
              @click="save"
            >
              儲存
            </b-button>
            <b-button
              v-ripple.400="'rgba(186, 191, 199, 0.15)'"
              type="reset"
              variant="outline-secondary"
              class="mr-1"
              @click="getMonitorTypes"
            >
              取消
            </b-button>
          </b-col>
        </b-row>
        <b-table
          small
          responsive
          :fields="columns"
          :items="monitorTypes"
          bordered
          sticky-header
          style="max-height: 650px"
        >
          <template #cell(max)="row">
            <b-form-input v-model.number="row.item.max" />
          </template>
          <template #cell(min)="row">
            <b-form-input v-model.number="row.item.min" />
          </template>
        </b-table>
      </b-tab>
    </b-tabs>
  </b-card>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script lang="ts">
import Vue from 'vue';
const Ripple = require('vue-ripple-directive');
import axios from 'axios';
import moment from 'moment';
import { isNumber } from 'highcharts';
import DatePicker from 'vue2-datepicker';
import 'vue2-datepicker/index.css';
import 'vue2-datepicker/locale/zh-tw';
import { CdxConfig } from './types';

interface CdxMonitorTypes {
  mt: string;
  name: string;
  min?: number;
  max?: number;
}

export default Vue.extend({
  components: {
    DatePicker,
  },
  directives: {
    Ripple,
  },
  data() {
    const configColumns = [
      {
        key: 'enable',
        lable: '自動上傳',
      },
      {
        key: 'user',
        label: '使用者',
      },
      {
        key: 'password',
        label: '密碼',
      },
      {
        key: 'siteCounty',
        label: 'siteCounty',
      },
      {
        key: 'siteID',
        label: 'siteID',
      },
    ];

    const columns = [
      {
        key: 'mt',
        label: '代碼',
      },
      {
        key: 'name',
        label: '名稱',
      },
      {
        key: 'min',
        label: '最小值',
      },
      {
        key: 'max',
        label: '最大值',
      },
    ];

    const cdxUploadColumns = [
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
    ];
    const monitorTypes = Array<CdxMonitorTypes>();
    const range = [moment().subtract(1, 'days').valueOf(), moment().valueOf()];

    let cdxConfig: CdxConfig = {
      enable: false,
      user: '',
      password: '',
      siteCounty: '',
      siteID: '',
    };
    return {
      display: false,
      configColumns,
      columns,
      cdxConfig,
      monitorTypes,
      range,
      cdxUploadColumns,
      cdxUploadLogs: [],
    };
  },
  async mounted() {
    await this.getCdxConfig();
    await this.getMonitorTypes();
    await this.getCdxUploadEvents();
  },
  methods: {
    async getCdxConfig() {
      try {
        let ret = await axios.get('/CdxConfig');
        if (ret.status === 200) {
          this.cdxConfig = ret.data;
        }
      } catch (err) {
        throw new Error(`$err`);
      }
    },
    async getMonitorTypes() {
      try {
        let res = await axios.get('/CdxMonitorTypes');
        if (res.status === 200) {
          this.monitorTypes = res.data;
        }
      } catch (err) {
        throw new Error(`$err`);
      }
    },
    justify(mt: any) {
      if (!isNumber(mt.min)) mt.min = undefined;
      if (!isNumber(mt.max)) mt.min = undefined;
    },
    async saveCdxConfig() {
      try {
        let ret = await axios.put('/CdxConfig', this.cdxConfig);
        if (ret.status === 200) {
          this.$bvModal.msgBoxOk('成功');
        }
      } catch (err) {
        throw new Error(`$err`);
      }
    },
    async save() {
      for (const mt of this.monitorTypes) {
        this.justify(mt);
      }
      try {
        let ret = await axios.put('/CdxMonitorTypes', this.monitorTypes);
        if (ret.status === 200) this.$bvModal.msgBoxOk('成功');
      } catch (err) {
        throw new Error(`$err`);
      }
    },
    async getCdxUploadEvents() {
      try {
        let src = 'S:CDX';
        let res = await axios.get(
          `/Alarms/${src}/1/${this.range[0]}/${this.range[1]}`,
        );
        if (res.status === 200) {
          this.cdxUploadLogs = res.data;
        }
      } catch (err) {
        throw new Error(`$err`);
      }
    },
  },
});
</script>

<style></style>
