<template>
  <b-card title="即時資訊">
    <b-table striped hover :fields="fields" :items="items">
      <template #cell(index)="data">
        {{ data.index + 1 }}
      </template>
    </b-table>
  </b-card>
</template>

<script>
import axios from 'axios';

export default {
  data() {
    return {
      fields: [
        {
          key: 'index',
          label: 'STT',
        },
        {
          key: 'desp',
          label: 'Thông số ',
          sortable: true,
        },
        {
          key: 'value',
          label: 'Giá trị đo',
          sortable: true,
        },
        {
          key: 'unit',
          label: 'Đơn vị',
          sortable: true,
        },
        {
          key: 'instrument',
          label: 'Thiết bị',
          sortable: true,
        },
        {
          key: 'status',
          label: 'Trạng thái',
          sortable: true,
        },
      ],
      items: [],
      timer: 0,
    };
  },
  mounted() {
    this.getRealtimeData();
    this.timer = setInterval(this.getRealtimeData, 1000);
  },
  beforeDestroy() {
    clearInterval(this.timer);
  },
  methods: {
    async getRealtimeData() {
      const ret = await axios.get('/MonitorTypeStatusList');
      this.items.splice(0, this.items.length);
      this.items = ret.data;
    },
  },
};
</script>

<style></style>
