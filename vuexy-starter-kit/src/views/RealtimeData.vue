<template>
  <b-card title="即時資訊">
    <b-row>
      <b-col>
        <b-table striped hover :fields="fields" :items="item1">
          <template #cell(index)="data">
            {{ data.index + 1 }}
          </template>
        </b-table>
      </b-col>
      <b-col>
        <b-table striped hover :fields="fields" :items="item2">
          <template #cell(index)="data">
            {{ middle + data.index + 1 }}
          </template>
        </b-table>
      </b-col>
    </b-row>
  </b-card>
</template>

<script>
import axios from 'axios'

export default {
  data() {
    return {
      fields: [
        {
          key: 'index',
          label: '#',
        },
        {
          key: 'desp',
          label: '測項',
          sortable: true,
        },
        {
          key: 'value',
          label: '測值',
          sortable: true,
        },
        {
          key: 'unit',
          label: '單位',
          sortable: true,
        },
        {
          key: 'status',
          label: '狀態',
          sortable: true,
        },
      ],
      items: [],
      item1: [],
      item2: [],
      middle: 0,
      timer: 0,
    }
  },
  mounted() {
    this.getRealtimeData()
    this.timer = setInterval(this.getRealtimeData, 1000)
  },
  beforeDestroy() {
    clearInterval(this.timer)
  },
  methods: {
    async getRealtimeData() {
      const ret = await axios.get('/MonitorTypeStatusList')
      this.items = ret.data
      this.middle = Math.floor(this.items.length / 2)
      this.item1 = this.items.slice(0, this.middle)
      this.item2 = this.items.slice(this.middle)
    },
  },
}
</script>

<style></style>
