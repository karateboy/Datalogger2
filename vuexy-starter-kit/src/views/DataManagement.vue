<template>
  <div>
    <b-card no-body>
      <b-tabs card>
        <b-tab title="資料上傳" active> <DataUpload /></b-tab>
        <b-tab title="資料回補"> <DataUpsert /></b-tab>
      </b-tabs>
    </b-card>
  </div>
</template>
<script lang="ts">
import Vue from 'vue';
import DataUpload from './DataUpload.vue';
import DataUpsert from './DataUpsert.vue';

export default Vue.extend({
  components: {
    DataUpload,
    DataUpsert,
  },
});
</script>
