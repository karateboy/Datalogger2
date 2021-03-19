<template>
  <div>
    <b-form @submit.prevent>
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
      <b-form-group
        label="排除測站:"
        label-for="excludeMonitors"
        label-cols="3"
      >
        <b-form-checkbox-group
          id="excludeMonitors"
          v-model="group.excludeMonitors"
          :options="monitorOptions"
        >
        </b-form-checkbox-group>
      </b-form-group>
      <b-form-group label="管理員:" label-for="admin" label-cols="3">
        <b-form-checkbox id="admin" v-model="group.admin" />
      </b-form-group>
      <b-form-group
        label="排除測項:"
        label-for="excludeMonitorTypes"
        label-cols="3"
      >
        <b-form-checkbox-group
          id="excludeMonitorTypes"
          v-model="group.excludeMonitorTypes"
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
            variant="outline-secondary"
            @click="reset"
          >
            取消
          </b-button>
        </b-col>
      </b-row>
    </b-form>
  </div>
</template>

<script>
import Vue from 'vue';
import { mapState } from 'vuex';
import axios from 'axios';
import Ripple from 'vue-ripple-directive';
import ToastificationContent from '@core/components/toastification/ToastificationContent.vue';
const emptyPassword = '';
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
    const group = {
      _id: '',
      name: '',
      admin: false,
      excludeMonitors: [],
      excludeMonitorTypes: [],
      abilities: [],
    };

    this.copyProp(group);

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
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapState('monitors', ['monitors']),
    btnTitle() {
      if (this.isNew) return '新增';
      return '更新';
    },
    monitorOptions() {
      let ret = [];
      for (const m of this.monitors) ret.push({ text: m.desc, value: m._id });
      return ret;
    },
    monitorTypeOptions() {
      let ret = [];
      for (const mt of this.monitorTypes)
        ret.push({ text: mt.desp, value: mt._id });
      return ret;
    },
    canUpsert() {
      if (!this.group._id) return false;
      if (!this.group.name) return false;
      return true;
    },
  },
  methods: {
    copyProp(group) {
      if (!this.isNew) {
        const self = this.currentGroup;
        group._id = self._id;
        group.name = self.name;
        group.admin = self.admin;
        group.excludeMonitors = self.excludeMonitors;
        group.excludeMonitorTypes = self.excludeMonitorTypes;
        group.abilities = self.abilities;
      }
    },
    reset() {
      this.copyProp(this.group);
    },

    upsert() {
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
        axios.put(`/Group/${this.currentGroup.Id}`, this.group).then(res => {
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
