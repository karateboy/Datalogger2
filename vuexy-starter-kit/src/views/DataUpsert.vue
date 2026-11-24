<template>
  <div>
    <b-form @submit.prevent>
      <b-row>
        <b-col>
          <b-form-group label="資料種類" label-for="dataType" label-cols-md="3">
            <v-select
              id="dataType"
              v-model="form.dataType"
              label="txt"
              :reduce="dt => dt.id"
              :options="dataTypes"
            />
          </b-form-group>
        </b-col>
        <b-col>
          <b-toast id="example-toast" title="BootstrapVue" static no-auto-hide>
            Hello, world! This is a toast message.
          </b-toast>
          <b-form-file
            v-model="form.uploadFile"
            v-b-tooltip
            :state="Boolean(checkFileName)"
            accept=".xlsx"
            browse-text="..."
            placeholder="選擇上傳檔案..."
            drop-placeholder="拖曳檔案至此..."
            :title="`${form.dataType === 'min' ? '分鐘' : '小時'}資料Excel表格`"
          ></b-form-file>
          <span v-if="!checkFileName" class="text-danger">檔名不符合所選資料種類</span>
        </b-col>
      </b-row>
      <b-row>
        <!-- submit and reset -->
        <b-col offset-md="3" class="pt-2">
          <b-button
            v-ripple.400="'rgba(255, 255, 255, 0.15)'"
            variant="primary"
            class="mr-1"
            :disabled="!checkFileName"
            @click="upload"
          >
            回補資料
          </b-button>
        </b-col>
      </b-row>
    </b-form>
  </div>
</template>
<script lang="ts">
import Vue from 'vue'
const Ripple = require('vue-ripple-directive')
import { mapGetters, mapMutations } from 'vuex'
import axios from 'axios'

export default Vue.extend({
  directives: {
    Ripple,
  },

  data() {
    const form: {
      dataType: string
      uploadFile: Blob | undefined
    } = {
      dataType: 'hour',
      uploadFile: undefined,
    }
    const dataTypes = [
      { id: 'min', txt: '分鐘資料' },
      { id: 'hour', txt: '小時資料' },
    ]
    const errorMessage = ''
    return {
      dataTypes,
      form,
      errorMessage,
    }
  },
  computed: {
    checkFileName() {
      if (!this.form.uploadFile) return false

      let file = this.form.uploadFile as any
      let filename = file.name as string
      if (this.form.dataType === 'min' && filename.indexOf('min') !== -1)
        return true
      else if (this.form.dataType === 'hour' && filename.indexOf('hour') !== -1)
        return true

      return false
    },
  },
  methods: {
    ...mapMutations(['setLoading']),
    async upload() {
      let formData = new FormData()
      formData.append('data', this.form.uploadFile as Blob)
      this.setLoading({ loading: true, message: '資料上傳中' })
      try {
        let min = this.form.dataType === 'min'
        let res = await axios.post(`/UpsertData/${min}`, formData, {
          headers: {
            'Content-Type': 'multipart/form-data',
          },
        })
        this.setLoading({ loading: false })

        if (res.status === 200) {
          this.$bvModal.msgBoxOk(`上傳成功`, {
            headerBgVariant: 'success',
          })
        } else {
          this.$bvModal.msgBoxOk(`上傳失敗 ${res.status} - ${res.statusText}`, {
            headerBgVariant: 'danger',
          })
        }
      } catch (err) {
        this.setLoading({ loading: false })
        this.$bvModal.msgBoxOk(`上傳失敗 ${err}`, {
          headerBgVariant: 'danger',
        })
      }
    },
  },
})
</script>

<style></style>
