<template>
  <div>
    <b-card title="EV-20-NE-1(B)地下水質" border-variant="success">
      <b-table :fields="fields" :items="items" hover striped> </b-table>
    </b-card>
  </div>
</template>
<script lang="ts">
import Vue from 'vue';

export default Vue.extend({
  components: {},
  data() {
    let fields = [
      {
        key: 'name',
        label: '檢測項目',
      },
      {
        key: 'unit',
        label: '單位',
      },
      {
        key: 'EV-20-NE-1(B)',
        label: '111年4月22日',
      },
      {
        key: 'EV-20-NE-1(B)_1',
        label: '111年7月8日',
      },
      {
        key: 'EV-20-NE-1(B)_2',
        label: '111年9月2日',
      },
      {
        key: 'method',
        label: '檢測方法',
      },
    ];
    let items = [
      {
        name: '水位',
        unit: 'm',
        'EV-20-NE-1(B)': 2.64,
        'EV-20-NE-1(B)_1': 1.647,
        'EV-20-NE-1(B)_2': 2.638,
        method: 'NIEA W103.56B',
      },
      {
        name: '水溫',
        unit: '℃',
        'EV-20-NE-1(B)': 26.3,
        'EV-20-NE-1(B)_1': 27.3,
        'EV-20-NE-1(B)_2': 27.5,
        method: 'NIEA W217.51A',
      },
      {
        name: '濁度',
        unit: 'NTU',
        'EV-20-NE-1(B)': 74.6,
        'EV-20-NE-1(B)_1': 57.1,
        'EV-20-NE-1(B)_2': 58.2,
        method: 'NIEA W103.56B',
      },
      {
        name: 'pH值',
        unit: '-',
        'EV-20-NE-1(B)': 7.5,
        'EV-20-NE-1(B)_1': 7.5,
        'EV-20-NE-1(B)_2': 7.8,
        method: 'NIEA W424.53A',
      },
      {
        name: '導電度',
        unit: 'μmho/cm',
        'EV-20-NE-1(B)': 2720,
        'EV-20-NE-1(B)_1': 2670,
        'EV-20-NE-1(B)_2': 10300,
        method: 'NIEA W203.51B',
      },
      {
        name: '溶氧量',
        unit: 'mg/L',
        'EV-20-NE-1(B)': 0.6,
        'EV-20-NE-1(B)_1': 0.9,
        'EV-20-NE-1(B)_2': 1,
        method: 'NIEA W455.52C',
      },
      {
        name: '氧化還原電位',
        unit: 'mv',
        'EV-20-NE-1(B)': -75.7,
        'EV-20-NE-1(B)_1': -115,
        'EV-20-NE-1(B)_2': -147,
        method: 'NIEA W103.56B',
      },
      {
        name: '總溶解固體',
        unit: 'mg/L',
        'EV-20-NE-1(B)': 1680,
        'EV-20-NE-1(B)_1': 1750,
        'EV-20-NE-1(B)_2': 6580,
        method: 'NIEA W210.58A',
      },
      {
        name: '鈣',
        unit: 'mg/L',
        'EV-20-NE-1(B)': 50.3,
        'EV-20-NE-1(B)_1': 54.6,
        'EV-20-NE-1(B)_2': 115,
        method: 'NIEA W311.54C',
      },
      {
        name: '鎂',
        unit: 'mg/L',
        'EV-20-NE-1(B)': 70.1,
        'EV-20-NE-1(B)_1': 71.8,
        'EV-20-NE-1(B)_2': 242,
        method: 'NIEA W311.54C',
      },
      {
        name: '鉀',
        unit: 'mg/L',
        'EV-20-NE-1(B)': 38,
        'EV-20-NE-1(B)_1': 40.3,
        'EV-20-NE-1(B)_2': 98.6,
        method: 'NIEA W311.54C',
      },
      {
        name: '鈉',
        unit: 'mg/L',
        'EV-20-NE-1(B)': 422,
        'EV-20-NE-1(B)_1': 418,
        'EV-20-NE-1(B)_2': 1900,
        method: 'NIEA W311.54C',
      },
      {
        name: '鹽度',
        unit: 'psu',
        'EV-20-NE-1(B)': 1.4,
        'EV-20-NE-1(B)_1': 1.4,
        'EV-20-NE-1(B)_2': 5.7,
        method: 'NIEA W447.20C',
      },
      {
        name: '氯鹽',
        unit: 'mg/L',
        'EV-20-NE-1(B)': 353,
        'EV-20-NE-1(B)_1': 404,
        'EV-20-NE-1(B)_2': 3030,
        method: 'NIEA W415.54B',
      },
      {
        name: '硫酸鹽',
        unit: 'mg/L',
        'EV-20-NE-1(B)': 286,
        'EV-20-NE-1(B)_1': 299,
        'EV-20-NE-1(B)_2': 562,
        method: 'NIEA W415.54B',
      },
      {
        name: '碳酸氫鹽',
        unit: 'mg/L',
        'EV-20-NE-1(B)': 674,
        'EV-20-NE-1(B)_1': 648,
        'EV-20-NE-1(B)_2': 615,
        method: 'NIEA W449.00B',
      },
      {
        name: '碳酸鹽',
        unit: 'mg/L',
        'EV-20-NE-1(B)': '<1.0',
        'EV-20-NE-1(B)_1': '<1.0',
        'EV-20-NE-1(B)_2': '<1.0',
        method: 'NIEA W449.00B',
      },
      {
        name: '游離二氧化碳',
        unit: 'mg/L',
        'EV-20-NE-1(B)': 34.9,
        'EV-20-NE-1(B)_1': 36,
        'EV-20-NE-1(B)_2': 16.3,
        method: '推算法',
      },
    ];
    return {
      fields,
      items,
    };
  },
  async mounted() {},
  methods: {},
});
</script>
