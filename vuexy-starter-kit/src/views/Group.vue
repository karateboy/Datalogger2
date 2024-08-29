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
        <b-form-checkbox-group
          id="excludeMonitors"
          v-model="group.monitors"
          :options="monitorOptions"
        >
        </b-form-checkbox-group>
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

    return {
      group,
      abilityOptions,
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
      }
    },
    reset() {
      this.copyProp(this.group);
    },
    testLineMessage() {
      console.info('testLineMessage', this.group.lineToken);
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
