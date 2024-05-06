<template>
  <div>
    <b-row>
      <b-col>
        <b-card title="有效資料擷取率">
          <b-form @submit.prevent>
            <b-row>
              <b-col>
                <b-form-group
                    label="資料擷取率:"
                    label-for="effectiveRatio"
                    label-size="lg"
                    label-class="font-weight-bold pt-0"
                    label-cols-md="3"
                >
                  <b-form-input
                      id="effectiveRatio"
                      v-model.number="form.effectiveRatio"
                  />
                </b-form-group>
              </b-col>
            </b-row>
            <br/>
            <b-row>
              <b-col offset-md="3">
                <b-button
                    v-ripple.400="'rgba(255, 255, 255, 0.15)'"
                    type="submit"
                    variant="primary"
                    class="mr-1"
                    :disabled="!canSaveEffectiveRatio"
                    @click="setEffectiveRatio"
                >
                  儲存
                </b-button>
              </b-col>
            </b-row>
          </b-form>
        </b-card>
      </b-col>
      <b-col>
        <b-card title="資料表分割">
          <b-form @submit.prevent>
            <b-row>
              <b-col>
                <b-form-group
                    label="分割年度:"
                    label-for="splitYear"
                    label-size="lg"
                    label-class="font-weight-bold pt-0"
                    label-cols-md="3"
                >
                  <b-form-input
                      id="splitYear"
                      v-model.number="form.splitYear"
                  />
                </b-form-group>
              </b-col>
            </b-row>
            <br/>
            <b-row>
              <b-col offset-md="3">
                <b-button
                    v-ripple.400="'rgba(255, 255, 255, 0.15)'"
                    type="submit"
                    variant="primary"
                    class="mr-1"
                    @click="splitTable"
                >
                  分割
                </b-button>
              </b-col>
            </b-row>
          </b-form>
        </b-card>
      </b-col>
    </b-row>

    <b-card v-if="aqiMonitorTypes.length !== 0" title="AQI測項">
      <b-form @submit.prevent>
        <b-row>
          <b-col>
            <b-form-group
                label="臭氧(ppm) 八小時平均值測項"
                label-for="monitorType"
                label-cols-md="3"
            >
              <v-select
                  id="monitorType"
                  v-model="aqiMonitorTypes[0]"
                  label="desp"
                  :reduce="mt => mt._id"
                  :options="activatedMonitorTypes"
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group
                label="臭氧(ppm) 小時平均值測項"
                label-for="monitorType"
                label-cols-md="3"
            >
              <v-select
                  id="monitorType"
                  v-model="aqiMonitorTypes[1]"
                  label="desp"
                  :reduce="mt => mt._id"
                  :options="activatedMonitorTypes"
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group
                label="PM2.5(μg/m3) 平均值測項"
                label-for="monitorType"
                label-cols-md="3"
            >
              <v-select
                  id="monitorType"
                  v-model="aqiMonitorTypes[2]"
                  label="desp"
                  :reduce="mt => mt._id"
                  :options="activatedMonitorTypes"
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group
                label="PM10(μg/m3 )平均值測項"
                label-for="monitorType"
                label-cols-md="3"
            >
              <v-select
                  id="monitorType"
                  v-model="aqiMonitorTypes[3]"
                  label="desp"
                  :reduce="mt => mt._id"
                  :options="activatedMonitorTypes"
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group
                label="CO(ppm) 8小時平均值測項"
                label-for="monitorType"
                label-cols-md="3"
            >
              <v-select
                  id="monitorType"
                  v-model="aqiMonitorTypes[4]"
                  label="desp"
                  :reduce="mt => mt._id"
                  :options="activatedMonitorTypes"
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group
                label="SO2(ppb) 小時平均值測項"
                label-for="monitorType"
                label-cols-md="3"
            >
              <v-select
                  id="monitorType"
                  v-model="aqiMonitorTypes[5]"
                  label="desp"
                  :reduce="mt => mt._id"
                  :options="activatedMonitorTypes"
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group
                label="SO2(ppb) 24小時平均值測項"
                label-for="monitorType"
                label-cols-md="3"
            >
              <v-select
                  id="monitorType"
                  v-model="aqiMonitorTypes[6]"
                  label="desp"
                  :reduce="mt => mt._id"
                  :options="activatedMonitorTypes"
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group
                label="NO2(ppb) 小時平均值測項"
                label-for="monitorType"
                label-cols-md="3"
            >
              <v-select
                  id="monitorType"
                  v-model="aqiMonitorTypes[7]"
                  label="desp"
                  :reduce="mt => mt._id"
                  :options="activatedMonitorTypes"
              />
            </b-form-group>
          </b-col>
        </b-row>
        <br/>
        <b-row>
          <b-col offset-md="3">
            <b-button
                v-ripple.400="'rgba(255, 255, 255, 0.15)'"
                type="submit"
                variant="primary"
                class="mr-1"
                :disabled="!canSaveEffectiveRatio"
                @click="setAqiMapping"
            >
              儲存
            </b-button>
          </b-col>
        </b-row>
      </b-form>
    </b-card>
    <b-card title="異常通報">
      <b-table :items="emails" :fields="fields" bordered>
        <template #thead-top>
          <b-tr>
            <b-td colspan="2">
              <b-button
                  variant="gradient-primary"
                  class="mr-1"
                  @click="newEmail"
              >
                新增
              </b-button>

              <b-button
                  variant="gradient-primary"
                  class="mr-1"
                  @click="saveEmail"
              >
                儲存
              </b-button>
              <b-button
                  variant="gradient-primary"
                  class="mr-1"
                  @click="testAllEmail"
              >
                測試全部
              </b-button>
            </b-td>
          </b-tr>
        </template>
        <template #cell(_id)="row">
          <b-form-input v-model="row.item._id"/>
        </template>
        <template #cell(operation)="row">
          <b-button
              variant="gradient-danger"
              class="mr-2"
              @click="deleteEmail(row.index)"
          >刪除
          </b-button
          >
          <b-button
              variant="gradient-info"
              class="mr-2"
              :disabled="!validateEmail(row.index)"
              @click="testEmail(row.index)"
          >測試
          </b-button
          >
        </template>
      </b-table>
      <br/>
      <b-table-simple bordered>
        <b-thead>
          <b-tr>
            <b-th>
              Line通報Token
            </b-th>
            <b-th>
              操作
            </b-th>
          </b-tr>
        </b-thead>
        <b-tbody>
          <b-tr>
            <b-td>
              <b-form-input
                  id="lineToken"
                  v-model="lineToken"
              />
            </b-td>
            <b-td>
              <b-button
                  v-ripple.400="'rgba(255, 255, 255, 0.15)'"
                  type="submit"
                  variant="primary"
                  class="mr-1"
                  @click="saveLineToken"
              >
                儲存
              </b-button>
              <b-button
                  v-ripple.400="'rgba(255, 255, 255, 0.15)'"
                  type="submit"
                  variant="primary"
                  class="mr-1"
                  @click="testLineToken"
              >
                測試
              </b-button>
            </b-td>
          </b-tr>
        </b-tbody>
      </b-table-simple>
    </b-card>
  </div>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script lang="ts">
import Vue from 'vue';
import axios from 'axios';
import {isNumber} from 'highcharts';
import {mapActions, mapGetters, mapMutations, mapState} from 'vuex';
import moment from "moment";

const Ripple = require('vue-ripple-directive');

interface EmailTarget {
  _id: string;
  topic: Array<string>;
}

const emailRegx =
    /^(([^<>()[\]\\.,;:\s@"]+(\.[^<>()[\]\\.,;:\s@"]+)*)|(".+"))@((\[[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\])|(([a-zA-Z\-0-9]+\.)+[a-zA-Z]{2,}))$/;

export default Vue.extend({
  directives: {
    Ripple,
  },
  data() {
    let emails = Array<EmailTarget>();
    let aqiMonitorTypes = new Array<string>();
    const fields = [
      {
        key: '_id',
        label: 'email',
      },
      {
        key: 'operation',
        label: '操作',
      },
    ];

    let splitYear = moment().year() - 2;
    return {
      form: {
        effectiveRatio: 0.75,
        splitYear,
      },
      selected: [],
      emails,
      fields,
      aqiMonitorTypes,
      disconnectCheckTime: '',
      lineToken: '',
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapGetters('monitorTypes', ['mtMap', 'activatedMonitorTypes']),
    canSaveEffectiveRatio(): boolean {
      if (!this.form.effectiveRatio) return false;

      if (!isNumber(this.form.effectiveRatio)) return false;

      if (this.form.effectiveRatio > 1 || this.form.effectiveRatio < 0)
        return false;

      return true;
    },
  },
  async mounted() {
    await this.getEffectiveRatio();
    await this.getAlertEmailTarget();
    await this.getLineToken();
    await this.fetchMonitorTypes();
    await this.getAqiMapping();
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapMutations(['setLoading']),
    async getEffectiveRatio() {
      const res = await axios.get('/SystemConfig/EffectiveRatio');
      this.form.effectiveRatio = res.data;
    },
    async setEffectiveRatio() {
      const res = await axios.post('/SystemConfig/EffectiveRatio', {
        id: '',
        value: this.form.effectiveRatio.toString(),
      });
      if (res.status === 200) {
        this.$bvModal.msgBoxOk('成功', {headerBgVariant: 'info'});
      } else {
        this.$bvModal.msgBoxOk(`失敗 ${res.status} - ${res.statusText}`, {
          headerBgVariant: 'danger',
        });
      }
    },
    async getAqiMapping() {
      try {
        const res = await axios.get('/SystemConfig/AqiMonitorTypes');
        if (res.status === 200) {
          this.aqiMonitorTypes = res.data;
        }
      } catch (err) {
        console.error(err);
      }
    },
    async setAqiMapping() {
      try {
        const res = await axios.post(
            '/SystemConfig/AqiMonitorTypes',
            this.aqiMonitorTypes,
        );
        if (res.status === 200) {
          this.$bvModal.msgBoxOk('成功更新');
        }
      } catch (err) {
        console.error(err);
      }
    },
    onEmailSelected(items: never[]) {
      this.selected = items;
    },
    async getAlertEmailTarget() {
      try {
        const res = await axios.get('/AlertEmailTargets');
        this.emails = res.data;
      } catch (err) {
        throw new Error('failed to get Alert email!');
      }
    },
    newEmail() {
      this.emails.push({
        _id: '',
        topic: [],
      });
    },
    deleteEmail(index: number) {
      this.emails.splice(index, 1);
    },
    validateEmail(index: number) {
      return emailRegx.test(this.emails[index]._id);
    },
    async saveEmail() {
      let filteredEmails = this.emails.filter(v => {
        if (!Boolean(v._id)) return false;
        return emailRegx.test(v._id.toLowerCase());
      });

      const res = await axios.post('/AlertEmailTargets', filteredEmails);
      if (res.status === 200) this.$bvModal.msgBoxOk('成功');
    },
    async testEmail(index: number) {
      const params = {
        email: this.emails[index]._id,
      };

      const res = await axios.get('/TestAlertEmail', {
        params,
      });
      if (res.status === 200) this.$bvModal.msgBoxOk('成功');
    },
    async testAllEmail() {
      try {
        const res = await axios.get('/TestAllAlertEmail');
        if (res.status === 200) await this.$bvModal.msgBoxOk('成功');
      } catch (err) {
        throw new Error('failed to test email!');
      }
    },
    async getLineToken() {
      try {
        const res = await axios.get('/SystemConfig/LineToken');
        this.lineToken = res.data;
      } catch (err) {
        throw new Error('failed to get Line Token!');
      }
    },
    async saveLineToken() {
      try {
        const res = await axios.post('/SystemConfig/LineToken', {
          id: '',
          value: this.lineToken,
        });
        if (res.status === 200) {
          await this.$bvModal.msgBoxOk('成功');
        }
      } catch (err) {
        console.error(err);
      }
    },
    async testLineToken() {
      try {
        const res = await axios.get(`/SystemConfig/LineToken/Verify/${this.lineToken}`);
        if (res.status === 200) {
          await this.$bvModal.msgBoxOk('成功');
        }
      } catch (err) {
        console.error(err);
      }
    },
    async splitTable() {
      try {
        this.setLoading({ loading: true });
        const res = await axios.post('/SystemConfig/SplitTable', {
          id: '',
          value: this.form.splitYear.toString(),
        });
        if (res.status === 200) {
          this.$bvModal.msgBoxOk('成功');
        }
      } catch (err) {
        this.$bvModal.msgBoxOk(`失敗:${err}`);
        console.error(err);
      } finally {
        this.setLoading({ loading: false });
      }
    },
  },
});
</script>
