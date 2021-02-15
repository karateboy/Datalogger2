<template>
  <div>
    <b-card title="測點管理" class="text-center">
      <b-table
        responsive
        :fields="columns"
        :items="monitors"
        bordered
        sticky-header
        style="max-height: 600px"
      >
        <template #cell(desc)="row">
          <b-form-input v-model="row.item.desc" @change="markDirty(row.item)" />
        </template>
      </b-table>
      <b-row>
        <b-col offset-md="3">
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
            @click="getMonitors"
          >
            取消
          </b-button>
        </b-col>
      </b-row>
    </b-card>
  </div>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script lang="ts">
import Vue from 'vue';
import Ripple from 'vue-ripple-directive';
import axios from 'axios';
/*
interface MonitorType {
  _id: string;
  desp: string;
  unit: string;
  prec: number;
  order: number;
  signalType: boolean;
  std_law?: number;
  std_internal?: number;
  zd_internal?: number;
  zd_law?: number;
  span?: number;
  span_dev_internal?: number;
  span_dev_law?: number;
  measuringBy?: Array<string>;
} */

export default Vue.extend({
  components: {},
  directives: {
    Ripple,
  },
  data() {
    const columns = [
      {
        key: '_id',
        label: '代碼',
      },
      {
        key: 'desc',
        label: '名稱',
        sortable: true,
      },
    ];
    const monitors = [];

    return {
      display: false,
      columns,
      monitors,
    };
  },
  mounted() {
    this.getMonitors();
  },
  methods: {
    getMonitors() {
      axios.get('/Monitors').then(res => {
        this.monitors = res.data;
      });
    },
    save() {
      const all = [];
      for (const m of this.monitors) {
        if (m.dirty) {
          all.push(axios.put(`/Monitor/${m._id}`, m));
        }
      }

      Promise.all(all).then(() => {
        this.getMonitors();
        this.$bvModal.msgBoxOk('成功');
      });
    },
    markDirty(item) {
      item.dirty = true;
    },
  },
});
</script>

<style></style>
