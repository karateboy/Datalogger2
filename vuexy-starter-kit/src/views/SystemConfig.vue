<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="12">
            <b-form-group
              label="資料擷取率:"
              label-for="effectiveRatio"
              label-size="lg"
              label-class="font-weight-bold pt-0"
            >
              <b-form-input
                id="effectiveRatio"
                v-model.number="form.effectiveRatio"
              />
            </b-form-group>
          </b-col>
        </b-row>
        <br />
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
          <b-form-input v-model="row.item._id" />
        </template>
        <template #cell(operation)="row">
          <b-button
            variant="gradient-danger"
            class="mr-2"
            @click="deleteEmail(row.index)"
            >刪除</b-button
          >
          <b-button
            variant="gradient-info"
            class="mr-2"
            :disabled="!validateEmail(row.index)"
            @click="testEmail(row.index)"
            >測試</b-button
          >
        </template>
      </b-table>
    </b-card>
  </div>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script lang="ts">
import Vue from 'vue';
const Ripple = require('vue-ripple-directive');
import axios from 'axios';
import { isNumber } from 'highcharts';

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

    return {
      form: {
        effectiveRatio: 0.75,
      },
      selected: [],
      emails,
      fields,
      disconnectCheckTime: '',
    };
  },
  computed: {
    canSaveEffectiveRatio(): boolean {
      if (!this.form.effectiveRatio) return false;

      if (!isNumber(this.form.effectiveRatio)) return false;

      if (this.form.effectiveRatio > 1 || this.form.effectiveRatio < 0)
        return false;

      return true;
    },
  },
  mounted() {
    this.getEffectiveRatio();
    this.getAlertEmailTarget();
  },
  methods: {
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
        this.$bvModal.msgBoxOk('成功', { headerBgVariant: 'info' });
      } else {
        this.$bvModal.msgBoxOk(`失敗 ${res.status} - ${res.statusText}`, {
          headerBgVariant: 'danger',
        });
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
        if (res.status === 200) this.$bvModal.msgBoxOk('成功');
      } catch (err) {
        throw new Error('failed to test email!');
      }
    },
  },
});
</script>
