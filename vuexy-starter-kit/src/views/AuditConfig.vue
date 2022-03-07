<template>
  <b-card no-body>
    <b-tabs pills card>
      <b-tab title="突波高值檢核" active class="pt-0">
        <h3 class="ml-2"><strong>說明:</strong>測值超過前後平均的絕對值範圍</h3>
        <b-table :items="spikeRules" :fields="spikeRuleFields" bordered>
          <template #thead-top>
            <b-tr>
              <b-td colspan="4">
                <b-button
                  variant="gradient-primary"
                  class="mr-1"
                  @click="newSpikeRule"
                >
                  新增
                </b-button>

                <b-button
                  variant="gradient-primary"
                  class="mr-1"
                  @click="saveRules('/SpikeRule', spikeRules)"
                >
                  儲存
                </b-button>
              </b-td>
            </b-tr>
          </template>
          <template #cell(_id)="row">
            <v-select
              id="monitorType"
              v-model="row.item._id.monitorType"
              label="desp"
              :reduce="mt => mt._id"
              :options="monitorTypes"
            />
          </template>
          <template #cell(enable)="row">
            <b-form-checkbox v-model="row.item.enable" />
          </template>
          <template #cell(abs)="row">
            <b-form-spinbutton
              v-model.number="row.item.abs"
              min="1"
              max="1000"
            ></b-form-spinbutton>
          </template>
          <template #cell(operation)="row">
            <b-button
              variant="gradient-danger"
              class="mr-2"
              @click="deleteRule('/SpikeRule', row.item._id, getSpikeRules)"
              >刪除</b-button
            >
          </template>
        </b-table>
      </b-tab>
      <b-tab title="定值檢核" class="pt-0">
        <h3 class="ml-2"><strong>說明:</strong>連續多筆值相等, 視為定值</h3>
        <b-table :items="constantRules" :fields="constantRuleFields" bordered>
          <template #thead-top>
            <b-tr>
              <b-td colspan="4">
                <b-button
                  variant="gradient-primary"
                  class="mr-1"
                  @click="newConstantRule"
                >
                  新增
                </b-button>

                <b-button
                  variant="gradient-primary"
                  class="mr-1"
                  @click="saveRules('/ConstantRule', constantRules)"
                >
                  儲存
                </b-button>
              </b-td>
            </b-tr>
          </template>
          <template #cell(_id)="row">
            <v-select
              id="monitorType"
              v-model="row.item._id.monitorType"
              label="desp"
              :reduce="mt => mt._id"
              :options="monitorTypes"
            />
          </template>
          <template #cell(enable)="row">
            <b-form-checkbox v-model="row.item.enable" />
          </template>
          <template #cell(count)="row">
            <b-form-spinbutton
              v-model.number="row.item.count"
              min="1"
              max="24"
            ></b-form-spinbutton>
          </template>
          <template #cell(operation)="row">
            <b-button
              variant="gradient-danger"
              class="mr-2"
              @click="
                deleteRule('/ConstantRule', row.item._id, getConstantRules)
              "
              >刪除</b-button
            >
          </template>
        </b-table>
      </b-tab>
      <b-tab title="變動檢核" class="pt-0">
        <h3 class="ml-2">
          <strong>說明:</strong
          >連續兩小時測值變化如超過系統設定之絕對值,該小時值註記為異常值
        </h3>
        <b-table :items="variationRules" :fields="variationRuleFields" bordered>
          <template #thead-top>
            <b-tr>
              <b-td colspan="4">
                <b-button
                  variant="gradient-primary"
                  class="mr-1"
                  @click="newVariationRule"
                >
                  新增
                </b-button>

                <b-button
                  variant="gradient-primary"
                  class="mr-1"
                  @click="saveRules('/VariationRule', variationRules)"
                >
                  儲存
                </b-button>
              </b-td>
            </b-tr>
          </template>
          <template #cell(_id)="row">
            <v-select
              id="monitorType"
              v-model="row.item._id.monitorType"
              label="desp"
              :reduce="mt => mt._id"
              :options="monitorTypes"
            />
          </template>
          <template #cell(enable)="row">
            <b-form-checkbox v-model="row.item.enable" />
          </template>
          <template #cell(abs)="row">
            <b-form-spinbutton
              v-model.number="row.item.abs"
              min="1"
              max="1000"
            ></b-form-spinbutton>
          </template>
          <template #cell(operation)="row">
            <b-button
              variant="gradient-danger"
              class="mr-2"
              @click="
                deleteRule('/VariationRule', row.item._id, getVariationRules)
              "
              >刪除</b-button
            >
          </template>
        </b-table>
      </b-tab>
    </b-tabs>
  </b-card>
</template>

<script lang="ts">
import Vue from 'vue';
import axios from 'axios';
import { mapState, mapActions } from 'vuex';
interface RuleID {
  monitor: string;
  monitorType: string;
}
interface SpikeRule {
  _id: RuleID;
  enable: boolean;
  abs: number;
}
interface ConstantRule {
  _id: RuleID;
  enable: boolean;
  count: number;
}
interface VariationRule {
  _id: RuleID;
  enable: boolean;
  abs: number;
}

export default Vue.extend({
  data() {
    let spikeRules = Array<SpikeRule>();
    let spikeRuleFields = [
      {
        key: '_id',
        label: '測項',
      },
      {
        key: 'enable',
        label: '啟用',
      },
      {
        key: 'abs',
        label: '絕對值',
      },
      {
        key: 'operation',
        label: '操作',
      },
    ];
    let constantRuleFields = [
      {
        key: '_id',
        label: '測項',
      },
      {
        key: 'enable',
        label: '啟用',
      },
      {
        key: 'count',
        label: '次數',
      },
      {
        key: 'operation',
        label: '操作',
      },
    ];
    let constantRules = Array<ConstantRule>();
    let variationRules = Array<VariationRule>();
    let variationRuleFields = spikeRuleFields;
    return {
      spikeRules,
      spikeRuleFields,
      constantRules,
      constantRuleFields,
      variationRules,
      variationRuleFields,
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapState('monitors', ['monitors']),
  },
  async mounted() {
    await this.fetchMonitorTypes();
    await this.fetchMonitors();
    await this.getSpikeRules();
    await this.getConstantRules();
    await this.getVariationRules();
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors']),

    newSpikeRule() {
      this.spikeRules.push({
        _id: {
          monitor: this.monitors[0]._id,
          monitorType: this.monitorTypes[0]._id,
        },
        enable: true,
        abs: 10,
      });
    },
    newConstantRule() {
      this.constantRules.push({
        _id: {
          monitor: this.monitors[0]._id,
          monitorType: this.monitorTypes[0]._id,
        },
        enable: true,
        count: 2,
      });
    },
    newVariationRule() {
      this.variationRules.push({
        _id: {
          monitor: this.monitors[0]._id,
          monitorType: this.monitorTypes[0]._id,
        },
        enable: true,
        abs: 10,
      });
    },
    async getSpikeRules() {
      try {
        let ret = await axios.get('/SpikeRules');
        this.spikeRules = ret.data;
      } catch (err) {
        throw new Error('failed to get spike rules');
      }
    },
    async getConstantRules() {
      try {
        let ret = await axios.get('/ConstantRules');
        this.constantRules = ret.data;
      } catch (err) {
        throw new Error('failed to get constant rules');
      }
    },
    async getVariationRules() {
      try {
        let ret = await axios.get('/VariationRules');
        this.variationRules = ret.data;
      } catch (err) {
        throw new Error('failed to get variation rules');
      }
    },
    async saveRules(url: string, rules: Array<any>) {
      let promiseList = Array<Promise<any>>();
      for (let rule of rules) {
        let p = axios.post(`${url}`, rule);
        promiseList.push(p);
      }
      let all = Promise.all(promiseList);
      try {
        await all;
        this.$bvModal.msgBoxOk('成功', { headerBgVariant: 'info' });
      } catch (err) {
        this.$bvModal.msgBoxOk(`失敗 ${err}`, {
          headerBgVariant: 'danger',
        });
        throw new Error('failed to save all rules');
      }
    },
    async deleteRule(url: string, id: RuleID, reget: () => void) {
      try {
        let ret = await axios.delete(`${url}/${id.monitor}/${id.monitorType}`);
        if (ret.status === 200) {
          this.$bvModal.msgBoxOk('成功', { headerBgVariant: 'info' });
          reget();
        } else {
          this.$bvModal.msgBoxOk(`失敗 ${ret.status} - ${ret.statusText}`, {
            headerBgVariant: 'danger',
          });
        }
      } catch (err) {
        throw new Error('failed to delete spike rule');
      }
    },
  },
});
</script>
