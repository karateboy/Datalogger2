<template>
  <div class="navbar-container d-flex content align-items-center">
    <!-- Nav Menu Toggler -->
    <ul class="nav navbar-nav d-xl-none">
      <li class="nav-item">
        <b-link class="nav-link" @click="toggleVerticalMenuActive">
          <feather-icon icon="MenuIcon" size="21" />
        </b-link>
      </li>
    </ul>

    <!-- Left Col -->
    <div
      class="bookmark-wrapper align-items-center flex-grow-1 d-none d-lg-flex"
    >
      <dark-Toggler class="d-none d-lg-block" />
      <h2 class="m-0">彰濱基線資訊系統</h2>
    </div>

    <b-navbar-nav class="nav align-items-center ml-auto">
      <b-nav-item-dropdown
        right
        toggle-class="d-flex align-items-center dropdown-user-link"
        class="dropdown-user"
      >
        <template #button-content>
          <div class="d-sm-flex d-none user-nav">
            <p class="user-name font-weight-bolder mb-0">
              {{ userInfo.name }}
            </p>
            <span class="user-status">{{ role }}</span>
          </div>
          <b-avatar
            size="40"
            variant="light-primary"
            badge
            class="badge-minimal"
            badge-variant="success"
          />
        </template>

        <b-dropdown-item
          link-class="d-flex align-items-center"
          @click="updateUser"
        >
          <feather-icon size="16" icon="UserIcon" class="mr-50" />
          <span>設定</span>
        </b-dropdown-item>

        <b-dropdown-divider />

        <b-dropdown-item link-class="d-flex align-items-center" @click="logout">
          <feather-icon size="16" icon="LogOutIcon" class="mr-50" />
          <span>登出</span>
        </b-dropdown-item>
      </b-nav-item-dropdown>
    </b-navbar-nav>
    <b-modal
      id="userModal"
      title="使用者設定"
      modal-class="modal-primary"
      hide-footer
      no-close-on-backdrop
      size="lg"
    >
      <user :is-new="false" :current-user="user" @updated="onUpdate"></user>
    </b-modal>
  </div>
</template>

<script>
import DarkToggler from '@core/layouts/components/app-navbar/components/DarkToggler.vue';
import axios from 'axios';
import { mapState } from 'vuex';
import jscookie from 'js-cookie';
import User from '../../views/User.vue';

export default {
  components: {
    // Navbar Components
    DarkToggler,
    User,
  },
  props: {
    toggleVerticalMenuActive: {
      type: Function,
      default: () => {},
    },
  },
  data() {
    let user = Object.assign({}, this.userInfo);
    return {
      user,
    };
  },
  computed: {
    ...mapState('user', ['userInfo']),
    role() {
      if (this.userInfo.isAdmin) return '系統管理員';

      return '使用者';
    },
  },
  methods: {
    logout() {
      axios.get('/logout').then(() => {
        jscookie.remove('authentication');
        this.$router.push('/login');
      });
    },
    updateUser() {
      this.user = Object.assign({}, this.userInfo);
      this.$bvModal.show('userModal');
    },
    onUpdate() {
      this.$bvModal.hide('userModal');
    },
  },
};
</script>
