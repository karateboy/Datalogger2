<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col lg="6" sm="12">
            <b-form-group label="年:" label-for="year" label-cols-md="3">
              <b-form-input id="year" v-model.number="year" type="number" />
            </b-form-group>
          </b-col>
          <b-col lg="6" sm="12">
            <b-form-group label="測點:" label-for="monitor" label-cols-md="3">
              <v-select
                id="monitor"
                v-model="monitor"
                label="desc"
                :reduce="m => m._id"
                :options="monitors"
              />
            </b-form-group>
          </b-col>
        </b-row>
      </b-form>
    </b-card>
    <b-card :title="`${year}年用電量`" class="text-center">
      <b-table responsive :fields="fields" :items="usageList" bordered striped>
        <template #thead-top
          ><b-tr>
            <b-td colspan="2">
              <b-button
                variant="primary"
                class="ml-2"
                :disabled="!Boolean(monitor)"
                @click="save"
              >
                儲存
              </b-button>
              <b-button
                variant="primary"
                class="ml-2"
                :disabled="!Boolean(monitor)"
                @click="load"
              >
                載入
              </b-button>
            </b-td></b-tr
          >
        </template>
        <template #cell(usage)="row">
          <b-form-input v-model.number="row.item.usage" type="number" />
        </template>
      </b-table>
    </b-card>
  </div>
</template>
<script lang="ts">
import Vue from 'vue';
import { mapState, mapActions } from 'vuex';
import moment from 'moment';
import axios from 'axios';

interface PowerUsageParam {
  monitor: string;
  year: number;
  data: Array<PowerUsageData>;
}
interface PowerUsageData {
  month: number;
  usage?: number;
}
export default Vue.extend({
  data() {
    let fields = [
      {
        key: 'month',
        label: '月份',
      },
      {
        key: 'usage',
        label:
          '月用電量(度) - 僅需輸入開始測量前的月份. 測量不全的月份, 則輸入實際與測量差額',
      },
    ];
    let year = 2022;
    let usageList = Array<PowerUsageData>();
    for (let i = 1; i <= 12; i++) {
      usageList.push({
        month: i,
      });
    }
    return {
      monitor: '',
      year,
      fields,
      usageList,
    };
  },
  computed: {
    ...mapState('monitors', ['monitors']),
  },
  async mounted() {
    await this.fetchMonitors();
  },
  methods: {
    ...mapActions('monitors', ['fetchMonitors', 'getActiveID', 'setActiveID']),
    async save() {
      try {
        const param: PowerUsageParam = {
          monitor: this.monitor,
          year: this.year,
          data: this.usageList,
        };

        let res = await axios.put('/MonitorPowerUsage', param);
        if (res.status === 200) {
          this.$bvModal.msgBoxOk('儲存成功');
        }
      } catch (err) {
        console.error(`${err}`);
        this.$bvModal.msgBoxOk('儲存失敗');
      }
    },
    async load() {
      try {
        let res = await axios.get(
          `/MonitorPowerUsage/${this.monitor}/${this.year}`,
        );
        if (res.status === 200) {
          this.$bvModal.msgBoxOk('成功');
          console.info(res.data);
          this.usageList = res.data;
        }
      } catch (err) {
        console.error(`${err}`);
        this.$bvModal.msgBoxOk('載入失敗');
      }
    },
  },
});
</script>
