<template>
  <b-card title="asdf">
    <b-table striped hover :items="items" />
  </b-card>
</template>

<script>
import { BCard, BTable } from 'bootstrap-vue'

export default {
  components: {
    BCard,
    BTable
  },
  data() {
    return {
      items: [
        { 測項: '二氧化硫', 測值: 1.23, 單位: 'ppb', 設備: 'T100', 狀態: "正常" },
        { 測項: '二氧化硫', 測值: 18.02, 單位: 'ppb', 設備: 'T200', 狀態: "正常" },
      ],
    }
  },
}
</script>

<style></style>
