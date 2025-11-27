<template>
  <div>
    <b-form @submit.prevent>
      <b-row>
        <b-col>
          <b-toast id="example-toast" title="BootstrapVue" static no-auto-hide>
            Hello, world! This is a toast message.
          </b-toast>
          <b-form-file
            v-model="form.uploadFile"
            :state="Boolean(form.uploadFile)"
            accept=".xlsx"
            browse-text="..."
            placeholder="選擇上傳檔案..."
            drop-placeholder="拖曳檔案至此..."
            v-b-tooltip
            title="分鐘資料Excel表格"
          ></b-form-file>
        </b-col>
      </b-row>
      <b-row>
        <!-- submit and reset -->
        <b-col offset-md="3" class="pt-2">
          <b-button
            v-ripple.400="'rgba(255, 255, 255, 0.15)'"
            variant="primary"
            class="mr-1"
            :disabled="!Boolean(form.uploadFile)"
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
import Vue from 'vue';
const Ripple = require('vue-ripple-directive');
import { mapMutations } from 'vuex';
import axios from 'axios';

export default Vue.extend({
  directives: {
    Ripple,
  },

  data() {
    const form: {
      uploadFile: Blob | undefined;
    } = {
      uploadFile: undefined,
    };
    return {
      form,
    };
  },
  computed: {},
  methods: {
    ...mapMutations(['setLoading']),
    async upload() {
      let formData = new FormData();
      formData.append('data', this.form.uploadFile as Blob);
      this.setLoading({ loading: true, message: '資料上傳中' });
      try {
        let res = await axios.post(`/UpsertData`, formData, {
          headers: {
            'Content-Type': 'multipart/form-data',
          },
        });
        this.setLoading({ loading: false });

        if (res.status === 200) {
          this.$bvModal.msgBoxOk(`上傳成功`, {
            headerBgVariant: 'success',
          });
        } else {
          this.$bvModal.msgBoxOk(`上傳失敗 ${res.status} - ${res.statusText}`, {
            headerBgVariant: 'danger',
          });
        }
      } catch (err) {
        this.setLoading({ loading: false });
        this.$bvModal.msgBoxOk(`上傳失敗 ${err}`, {
          headerBgVariant: 'danger',
        });
      }
    },
  },
});
</script>

<style></style>
