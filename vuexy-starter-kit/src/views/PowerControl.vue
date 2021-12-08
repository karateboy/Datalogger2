<template>
  <b-card
    header="電源控制"
    header-class="h4 display text-center"
    border-variant="primary"
    header-bg-variant="white"
  >
    <b-row class="p-1">
      <b-col v-for="equipment in equipmentList" :key="equipment.name" cols="3">
        <b-table-simple borderless>
          <b-tbody>
            <b-tr>
              <b-td rowspan="4"><b-img :src="equipment.img" fluid-grow /></b-td>
            </b-tr>
            <b-tr
              ><b-td>{{ equipment.name }}</b-td></b-tr
            >
            <b-tr
              ><b-td>{{ equipment.power }}</b-td></b-tr
            >
            <b-tr
              ><b-td><b-form-checkbox v-model="equipment.on" switch /></b-td>
            </b-tr>
          </b-tbody>
        </b-table-simple>
      </b-col>
    </b-row>
  </b-card>
</template>

<script lang="ts">
import Vue from 'vue';
export default Vue.extend({
  data() {
    return {
      equipmentList: [
        {
          img: '/images/ac.png',
          name: '冷氣1',
          power: '電流2A',
          on: true,
        },
        {
          img: '/images/ac.png',
          name: '冷氣2',
          power: '電流2A',
          on: true,
        },
        {
          img: '/images/refregrator.png',
          name: '冰箱',
          power: '電流2A',
          on: true,
        },
        {
          img: '/images/plug.png',
          name: '插座1',
          power: '電流2A',
          on: true,
        },
        {
          img: '/images/plug.png',
          name: '插座2',
          power: '電流2A',
          on: true,
        },
        {
          img: '/images/plug.png',
          name: '電流1',
          power: '電流2A',
          on: true,
        },
        {
          img: '/images/plug.png',
          name: '電流2',
          power: '電流2A',
          on: true,
        },
      ],
    };
  },
});
</script>
