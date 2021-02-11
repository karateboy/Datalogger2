<template>
  <div>
    <b-form class="form_class">
      <b-row>
        <b-col cols="12">
          <b-form-group label="帳號:" label-for="account" label-cols="3">
            <b-input
              id="account"
              v-model="user._id"
              :state="Boolean(user._id)"
              aria-describedby="account-feedback"
              :readonly="!isNew"
            ></b-input>
            <b-form-invalid-feedback>帳號不能是空的</b-form-invalid-feedback>
          </b-form-group>
        </b-col>
      </b-row>
      <b-row>
        <b-col cols="12">
          <b-form-group
            label="顯示名稱:"
            label-for="name"
            :state="isDisplayNameValid"
            label-cols="3"
          >
            <b-input
              id="name"
              v-model="user.name"
              :state="Boolean(user.name)"
              aria-describedby="displayName-feedback"
            ></b-input>
            <b-form-invalid-feedback
              >顯示名稱不能是空的</b-form-invalid-feedback
            >
          </b-form-group>
        </b-col>
      </b-row>
      <b-row>
        <b-col cols="12">
          <b-form-group
            :label="passwordLabel"
            label-for="password"
            :state="isPasswordValid"
            label-cols="3"
          >
            <b-input
              id="password"
              v-model="user.password"
              type="password"
              :state="isPasswordValid"
              aria-describedby="password-feedback"
            ></b-input>
            <b-form-invalid-feedback id="password-feedback">{{
              passwordInvalidReason
            }}</b-form-invalid-feedback>
          </b-form-group>
        </b-col>
      </b-row>
      <b-row>
        <b-col cols="12">
          <b-form-group
            label="重新輸入密碼:"
            label-for="password2"
            label-cols="3"
          >
            <b-input
              id="password2"
              v-model="user.confirmPassword"
              type="password"
              :state="isPasswordValid"
              aria-describedby="password-feedback"
            ></b-input>
          </b-form-group>
        </b-col>
      </b-row>
      <b-row>
        <b-col>
          <b-form-group
            v-show="!isMyself"
            label="管理者:"
            label-for="admin"
            label-cols="3"
          >
            <b-check v-model="user.isAdmin" size="lg"></b-check>
          </b-form-group>
        </b-col>
      </b-row>
      <b-row>
        <b-col>
          <b-button variant="primary" :disabled="!canUpsert" @click="upsert">{{
            btnTitle
          }}</b-button>
        </b-col>
        <b-col>
          <b-button v-show="!isNew" variant="info" @click="reset"
            >取消</b-button
          >
        </b-col>
      </b-row>
    </b-form>
  </div>
</template>
<style scoped>
.form_class {
  color: #192e5b;
}
</style>
<script>
import Vue from 'vue';
import { mapState } from 'vuex';
import axios from 'axios';

const emptyPassword = '';
export default Vue.extend({
  props: {
    isNew: Boolean,
    currentUser: {
      type: Object,
      default: () => {},
    },
  },
  data() {
    const user = {
      _id: '',
      name: '',
      password: '',
      confirmPassword: '',
      isAdmin: false,
    };

    if (!this.isNew) {
      const self = this.currentUser;
      user._id = self._id;
      user.name = self.name;
      user.isAdmin = self.isAdmin;
    }

    return {
      user,
    };
  },
  computed: {
    ...mapState('user', ['userInfo']),
    passwordLabel() {
      if (this.isNew) return '密碼:';
      return '變更密碼:';
    },
    isPasswordValid() {
      if (!this.isNew) return true;

      if (this.user.password === this.user.confirmPassword) return true;
      return false;
    },
    passwordInvalidReason() {
      if (this.user.Password !== this.user.ConfirmPassword) {
        return '密碼和重新輸入必須一致';
      }
      return '';
    },
    canUpsert() {
      return this.isPasswordValid;
    },
    btnTitle() {
      if (this.isNew) return '新增';
      return '更新';
    },
    isMyself() {
      if (this.isNew) return false;

      if (this.user._id === this.userInfo._id) return true;
      return false;
    },
    isAdmin() {
      return this.userinfo.isAdmin;
    },
  },
  methods: {
    reset() {
      if (!this.isNew) {
        const self = this.currentUser;
        this.user._id = self._id;
        this.user.name = self.name;
        this.user.isAdmin = self.isAdmin;
      }
    },

    upsert() {
      this.user.LinkIds = this.linkinfo.join();
      if (this.isNew) {
        axios.post(`/User`, this.user).then(res => {
          if (res.status === 200) {
            this.$bvModal.msgBoxOk('成功', { headerBgVariant: 'primary' });
          } else {
            this.$bvModal.msgBoxOk('失敗', { headerBgVariant: 'danger' });
          }
          this.$emit('created');
        });
      } else {
        if (this.user.Password === emptyPassword) {
          this.user.Password = '';
          this.user.ConfirmPassword = '';
        }

        axios.put(`/User/${this.currentUser.Id}`, this.user).then(res => {
          if (res.status === 200) {
            this.$bvModal.msgBoxOk('成功', { headerBgVariant: 'primary' });
            if (this.currentUser.Id === this.userinfo.Id) {
              axios
                .get('/account')
                .then(res1 => {
                  const ret = res1.data;
                  this.updateUserInfo(ret);
                })
                .catch(err => {
                  throw Error(err);
                });
            }
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
