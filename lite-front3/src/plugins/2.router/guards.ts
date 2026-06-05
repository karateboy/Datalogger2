import type {RouteNamedMap, _RouterTyped} from 'unplugin-vue-router'
import {canNavigate} from '@layouts/plugins/casl'
import { useUserStore} from "@/stores/user";


export const setupGuards = (router: _RouterTyped<RouteNamedMap & { [key: string]: any }>) => {

  // 👉 router.beforeEach
  // Docs: https://router.vuejs.org/guide/advanced/navigation-guards.html#global-before-guards
  router.beforeEach(to => {
    const userStore = useUserStore()

    if (userStore.logined)
      return


    /*
      If user is logged in and is trying to access login like page, redirect to home
      else allow visiting the page
      (WARN: Don't allow executing further by return statement because next code will check for permissions)
     */
    if (!userStore.logined && to.name !== 'login')
      return {name: 'login'}

  })
}
