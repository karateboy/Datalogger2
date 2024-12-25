<template>
  <div>
    <b-form @submit.prevent>
      <b-form-group label="父群組:" label-for="parent" label-cols="3">
        <v-select
          id="group"
          v-model="group.parent"
          label="name"
          :reduce="grp => grp._id"
          :options="groupList"
        />
      </b-form-group>
      <b-form-group label="群組ID:" label-for="account" label-cols="3">
        <b-input
          id="account"
          v-model="group._id"
          :state="Boolean(group._id)"
          aria-describedby="account-feedback"
          :readonly="!isNew"
        ></b-input>
        <b-form-invalid-feedback>群組ID不能是空的</b-form-invalid-feedback>
      </b-form-group>
      <b-form-group
        label="顯示名稱:"
        label-for="name"
        :state="Boolean(group.name)"
        label-cols="3"
      >
        <b-input
          id="name"
          v-model="group.name"
          :state="Boolean(group.name)"
          aria-describedby="displayName-feedback"
        ></b-input>
        <b-form-invalid-feedback>顯示名稱不能是空的</b-form-invalid-feedback>
      </b-form-group>
      <b-form-group label="測站:" label-for="monitors" label-cols="3">
        <v-select
          id="monitors"
          v-model="group.monitors"
          label="text"
          :reduce="m => m.value"
          :options="monitorOptions"
          :close-on-select="false"
          multiple></v-select>
      </b-form-group>
      <b-form-group label="管理員:" label-for="admin" label-cols="3">
        <b-form-checkbox id="admin" v-model="group.admin" />
      </b-form-group>
      <b-form-group label="測項:" label-for="monitorTypes" label-cols="3">
        <b-form-checkbox-group
          id="monitorTypes"
          v-model="group.monitorTypes"
          :options="monitorTypeOptions"
        >
        </b-form-checkbox-group>
      </b-form-group>
      <b-form-group label="權限:" label-for="abilities" label-cols="3">
        <b-form-checkbox-group
          id="abilities"
          v-model="group.abilities"
          :options="abilityOptions"
        >
        </b-form-checkbox-group>
      </b-form-group>
      <b-form-group label="LINE Token:" label-for="lineToken" label-cols="3">
        <b-input
          id="lineToken"
          v-model="group.lineToken"
          aria-describedby="displayName-feedback"
        ></b-input>
      </b-form-group>
      <b-form-group
        label="不重複發送時間(分鐘):"
        label-for="lineNotifyColdPeriod"
        label-cols="3"
      >
        <b-input
          id="lineNotifyColdPeriod"
          v-model.number="group.lineNotifyColdPeriod"
          type="number"
          aria-describedby="displayName-feedback"
        ></b-input>
      </b-form-group>
      <b-form-group label="台南管制編號:" label-for="control-no" label-cols="3">
        <b-input
            id="control-no"
            v-model="group.controlNo"
            aria-describedby="displayName-feedback"
        ></b-input>
      </b-form-group>
      <b-row>
        <b-col offset-md="3">
          <b-button
            v-ripple.400="'rgba(255, 255, 255, 0.15)'"
            type="submit"
            variant="primary"
            class="mr-1"
            :disabled="!canUpsert"
            @click="upsert"
          >
            {{ btnTitle }}
          </b-button>
          <b-button
            v-ripple.400="'rgba(186, 191, 199, 0.15)'"
            class="mr-1"
            variant="outline-secondary"
            @click="reset"
          >
            取消
          </b-button>
          <b-button
            variant="info"
            :disabled="group.lineToken === undefined || group.lineToken === ''"
            @click="testLineMessage"
          >
            測試LINE訊息
          </b-button>
        </b-col>
      </b-row>
    </b-form>
  </div>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script lang="ts">
import Vue from 'vue';
import { mapState, mapGetters } from 'vuex';
import axios from 'axios';
import { Group, TextStrValue } from './types';
const Ripple = require('vue-ripple-directive');
import ToastificationContent from '@core/components/toastification/ToastificationContent.vue';

export default Vue.extend({
  directives: {
    Ripple,
  },

  props: {
    isNew: Boolean,
    currentGroup: {
      type: Object,
      default: () => {},
    },
  },
  data() {
    const group: Group = {
      _id: '',
      name: '',
      admin: false,
      monitors: [],
      monitorTypes: [],
      abilities: [],
      parent: undefined,
      lineToken: undefined,
      lineNotifyColdPeriod: 30,
      controlNo: undefined,
    };

    const abilityOptions = [
      {
        text: '儀表板',
        value: {
          action: 'read',
          subject: 'Dashboard',
        },
      },
      {
        text: '資料',
        value: {
          action: 'read',
          subject: 'Data',
        },
      },
      {
        text: '設定警報',
        value: {
          action: 'set',
          subject: 'Alarm',
        },
      },
    ];

    const countyOptions = [
      { value: undefined, text: '請選擇一個縣市' },
      { value: '基隆市', text: '基隆市' },
      { value: '臺北市', text: '臺北市' },
      { value: '新北市', text: '新北市' },
      { value: '桃園市', text: '桃園市' },
      { value: '新竹市', text: '新竹市' },
      { value: '新竹縣', text: '新竹縣' },
      { value: '苗栗縣', text: '苗栗縣' },
      { value: '臺中市', text: '臺中市' },
      { value: '彰化縣', text: '彰化縣' },
      { value: '南投縣', text: '南投縣' },
      { value: '雲林縣', text: '雲林縣' },
      { value: '嘉義市', text: '嘉義市' },
      { value: '嘉義縣', text: '嘉義縣' },
      { value: '臺南市', text: '臺南市' },
      { value: '高雄市', text: '高雄市' },
      { value: '屏東縣', text: '屏東縣' },
      { value: '宜蘭縣', text: '宜蘭縣' },
      { value: '花蓮縣', text: '花蓮縣' },
      { value: '臺東縣', text: '臺東縣' },
      { value: '澎湖縣', text: '澎湖縣' },
      { value: '金門縣', text: '金門縣' },
      { value: '連江縣', text: '連江縣' },
    ];
    return {
      group,
      abilityOptions,
      countyOptions,
      groupList: Array<Group>(),
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapState('monitors', ['monitors']),
    ...mapGetters('monitors', ['mMap']),
    btnTitle(): string {
      if (this.isNew) return '新增';
      return '更新';
    },
    monitorOptions(): Array<any> {
      const ret = Array<TextStrValue>();
      let monitors = this.monitors;
      if (this.group.parent) {
        const parentGroup = this.groupList.find((group, index) => {
          return group._id === this.group.parent;
        }) as Group;

        monitors = this.monitors.filter((m: any) => {
          if (parentGroup && parentGroup.monitors)
            return parentGroup.monitors.indexOf(m._id) !== -1;
          return false;
        });
      }

      for (const m of monitors) ret.push({ text: m.desc, value: m._id });
      return ret;
    },
    monitorTypeOptions(): Array<TextStrValue> {
      const ret = Array<TextStrValue>();

      let monitorTyes: Array<any> = this.monitorTypes;
      if (this.group.parent) {
        const parentGroup = this.groupList.find((group, index) => {
          return group._id === this.group.parent;
        }) as Group;

        monitorTyes = this.monitorTypes.filter((mt: any) => {
          if (parentGroup && parentGroup.monitorTypes)
            return parentGroup.monitorTypes.indexOf(mt._id) !== -1;
          return false;
        });
      }

      for (const mt of monitorTyes) ret.push({ text: mt.desp, value: mt._id });

      return ret;
    },
    canUpsert(): boolean {
      if (!this.group._id) return false;
      if (!this.group.name) return false;
      return true;
    },
  },
  mounted() {
    this.copyProp(this.group);
    this.getGroupList();
  },
  methods: {
    copyProp(group: Group): void {
      if (!this.isNew) {
        const self = this.currentGroup;
        group._id = self._id;
        group.name = self.name;
        group.admin = self.admin;
        group.monitors = self.monitors;
        group.monitorTypes = self.monitorTypes;
        group.abilities = self.abilities;
        group.parent = self.parent;
        group.lineToken = self.lineToken;
        group.lineNotifyColdPeriod = self.lineNotifyColdPeriod;
        group.controlNo = self.controlNo;
      }
    },
    reset() {
      this.copyProp(this.group);
    },
    testLineMessage() {
      axios.get(`/TestLINE/${this.group.lineToken}`).then(res => {
        if (res.status === 200) {
          this.$toast({
            component: ToastificationContent,
            props: {
              title: '成功',
              icon: 'GroupIcon',
            },
          });
        } else {
          this.$bvModal.msgBoxOk('失敗', { headerBgVariant: 'danger' });
        }
      });
    },
    async getGroupList() {
      const res = await axios.get('/Groups');
      if (res.status == 200) this.groupList = res.data;
    },
    sanityCheck() {
      this.group.monitors = this.group.monitors.filter(m => this.mMap.get(m));
    },
    upsert() {
      this.sanityCheck();
      if (this.isNew) {
        axios.post(`/Group`, this.group).then(res => {
          if (res.status === 200) {
            this.$bvModal.msgBoxOk('成功', { headerBgVariant: 'primary' });
          } else {
            this.$bvModal.msgBoxOk('失敗', { headerBgVariant: 'danger' });
          }
          this.$emit('created');
        });
      } else {
        axios.put(`/Group/${this.currentGroup._id}`, this.group).then(res => {
          if (res.status === 200) {
            this.$toast({
              component: ToastificationContent,
              props: {
                title: '成功',
                icon: 'GroupIcon',
              },
            });
          } else {
            this.$bvModal.msgBoxOk('失敗', { headerBgVariant: 'danger' });
          }
          this.$emit('updated');
        });
      }
    },
  },
});
</script>
