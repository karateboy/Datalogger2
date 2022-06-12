<template>
  <b-card no-body>
    <b-tabs card>
      <b-tab title="CDX上傳設定" active>
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
import { isNumber } from 'highcharts';

interface CdxMonitorTypes {
  mt: string;
  name: string;
  min?: number;
  max?: number;
}

interface CdxConfig {
  enable: boolean;
  user: string;
  password: string;
  siteCounty: string;
  siteID: string;
}

export default Vue.extend({
  components: {},
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
    const monitorTypes = Array<CdxMonitorTypes>();

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
    };
  },
  async mounted() {
    await this.getCdxConfig();
    this.getMonitorTypes();
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
  },
});
</script>

<style></style>
