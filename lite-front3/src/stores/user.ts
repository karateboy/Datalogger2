import axios from 'axios'
import {User} from '@/types'
import { defineStore } from 'pinia'

interface State {
  logined: boolean,
  userInfo: User
}

export const useUserStore = defineStore('userStore', {
  // 转换为函数
  state: (): State => ({
    logined: false,
    userInfo: {
      _id: '',
      name: '',
      phone: '',
      isAdmin: false,
      group: '',
      monitorTypeOfInterest: [],
      windField: false,
    }
  }),
  getters: {},
  actions: {
    async getUserInfo() {
      try {
        const res = await axios.get(`/User/${this.userInfo._id}`)
        if(res.status === 200) {
          this.userInfo = res.data.data
        }
      } catch (err) {
        console.error(err)
      }
    },
    async tryLogin(cred:{ user: string, password: string }){
      try {
        let res = await axios.post('/login', cred)
        if(res.status == 200) {
          const ret = res.data
          const userData = ret.userData
          const userInfo = userData.user
          if(ret.ok){
            this.userInfo = userInfo
            this.logined = true;
            return true;
          }else{
            return false;
          }
        }
      }catch(err) {
        console.error(err)
        return false;
      }
    }
  },
})
