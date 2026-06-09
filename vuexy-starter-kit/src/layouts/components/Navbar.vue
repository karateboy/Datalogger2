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
      <h2 class="m-0">{{ $t('title') }}</h2>
      <sub class="text-muted">{{ version }}</sub>
    </div>

    <b-navbar-nav class="nav align-items-center ml-auto">
      <b-nav-item-dropdown
        right
        toggle-class="d-flex align-items-center dropdown-user-link"
        class="dropdown-user"
      >
        <template #button-content>
          <feather-icon size="16" icon="GlobeIcon" class="mr-50" />
        </template>

        <b-dropdown-item
          v-for="lang in languages"
          :key="lang.value"
          link-class="d-flex align-items-center"
          @click="changeLang(lang)"
        >
          <span>{{ lang.label }}</span>
        </b-dropdown-item>
      </b-nav-item-dropdown>
    </b-navbar-nav>

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

        <b-dropdown-divider />

        <b-dropdown-item link-class="d-flex align-items-center" @click="logout">
          <feather-icon size="16" icon="LogOutIcon" class="mr-50" />
          <span>登出</span>
        </b-dropdown-item>
      </b-nav-item-dropdown>
    </b-navbar-nav>
  </div>
</template>

<script>
import DarkToggler from '@core/layouts/components/app-navbar/components/DarkToggler.vue'
import axios from 'axios'
import { mapState } from 'vuex'
import jscookie from 'js-cookie'

export default {
  components: {
    // Navbar Components
    DarkToggler,
  },
  props: {
    toggleVerticalMenuActive: {
      type: Function,
      default: () => {},
    },
  },
  data() {
    const languages = [
      { value: 'en', label: 'English' },
      { value: 'zh', label: '中文' },
      { value: 'vn', label: 'Tiếng Việt' },
    ]
    return {
      languages,
      version: '1.0.0',
    }
  },
  computed: {
    ...mapState('user', ['userInfo']),
    role() {
      if (this.userInfo.isAdmin) return '系統管理員'

      return '使用者'
    },
  },
  async mounted() {
    await this.getVersion()
  },
  methods: {
    async getVersion() {
      let res = await axios.get('/version')
      if (res.status === 200) this.version = res.data.version
    },
    logout() {
      axios.get('/logout').then(() => {
        jscookie.remove('authentication')
        this.$router.push('/login')
      })
    },
    changeLang(lang) {
      localStorage.setItem('locale', lang.value)
      this.$i18n.locale = lang.value
    },
  },
}
</script>
