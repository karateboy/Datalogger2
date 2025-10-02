<template>
  <div>
    <b-card title="測項群組管理" class="text-center">
      <b-table
        small
        :fields="columns"
        :items="monitorTypeGroups"
        select-mode="single"
        selectable
        selected-variant="info"
        bordered
        @row-selected="onMtSelected"
      >
        <template #thead-top>
          <b-tr>
            <b-td colspan="3" class="text-center">
              <b-button
                  class="bg-gradient-primary mr-1"
                  @click="newMonitorTypeGroup"
              >
                新增
              </b-button>
              <b-button
                  class="bg-gradient-info mr-1"
                  @click="save"
              >
                儲存全部
              </b-button>
            </b-td>
          </b-tr>
        </template>
        <template #cell(_id)="row">
          <b-button
              class="bg-gradient-info m-50"
              size="sm"
              @click="saveMtg(row.item)"
          >
            儲存
            <info-icon ></info-icon>
          </b-button>
          <b-button
            class="bg-gradient-danger m-50"
            size="sm"
            @click="removeMtg(row.item)"
          >
            刪除
          </b-button>
        </template>
        <template #cell(name)="row">
          <b-form-input v-model="row.item.name" @change="markDirty(row.item)" />
        </template>
        <template #cell(mts)="row">
          <v-select
              id="monitorType"
              v-model="row.item.mts"
              label="desp"
              :reduce="mt => mt._id"
              :options="monitorTypes"
              :close-on-select="false"
              multiple
              @input="markDirty(row.item)"
          />
        </template>
      </b-table>
    </b-card>
  </div>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<style scoped>

</style>
<script lang="ts">
import Vue from 'vue';
const Ripple = require('vue-ripple-directive');
import axios from 'axios';
import { MonitorType, ThresholdConfig } from './types';
import { isNumber } from 'highcharts';
import {mapActions, mapGetters, mapState} from "vuex";

interface MonitorTypeGroup {
  _id: string;
  name: string;
  mts: string[];
}
interface EditMonitorTypeGroup extends MonitorTypeGroup {
  dirty?: boolean;
}

export default Vue.extend({
  components: {},
  directives: {
    Ripple,
  },
  data() {
    const columns = [
      {
        key: '_id',
        label: '操作',
      },
      {
        key: 'name',
        label: '名稱',
      },
      {
        key: 'mts',
        label: '包含測項',
        thStyle: { width: '80%' },
      },
    ];
    const monitorTypeGroups = Array<EditMonitorTypeGroup>();

    return {
      display: false,
      columns,
      monitorTypeGroups,
      selected: Array<MonitorTypeGroup>(),
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
  },
  async mounted() {
    await this.fetchMonitorTypes();
    await this.getMonitorTypeGroups();
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    getMonitorTypeGroups() {
      axios.get('/MonitorTypeGroups').then(res => {
        this.monitorTypeGroups = res.data;
        for (const mt of this.monitorTypeGroups) {
          mt.dirty = false;
        }
      });
    },
    justify(mtg: MonitorTypeGroup) {

    },
    getNewId() {
      let id = '';
      do{
        id = `mtg-${Math.random().toString(36).substring(2, 15)}`;
      }while(this.monitorTypeGroups.find(mtg=>mtg._id===id));
      return id;
    },
    newMonitorTypeGroup() {
      const newMt: EditMonitorTypeGroup = {
        _id: this.getNewId(),
        name: '',
        mts: [],
        dirty: true,
      };
      this.monitorTypeGroups.push(newMt);
    },
    save() {
      const all = [];
      for (const mtg of this.monitorTypeGroups) {
        if (mtg.dirty) {
          this.justify(mtg);
          all.push(axios.put(`/MonitorTypeGroup/${mtg._id}`, mtg));
        }
      }

      Promise.all(all).then(() => {
        this.getMonitorTypeGroups();
        this.$bvModal.msgBoxOk('成功');
      });
    },
    async saveMtg(mtg: EditMonitorTypeGroup) {
      try{
        await axios.put(`/MonitorTypeGroup/${mtg._id}`, mtg);
        await this.$bvModal.msgBoxOk('成功');
      }catch (error) {
        await this.$bvModal.msgBoxOk(`失敗 - ${error}`);
      }
    },
    markDirty(item: EditMonitorTypeGroup) {
      item.dirty = true;
    },
    onMtSelected(items: Array<MonitorTypeGroup>) {
      this.selected = items;
    },
    async removeMtg(mtg: MonitorTypeGroup) {
      let deletedMts = this.selected.map(p => p._id);
      let ret = await this.$bvModal.msgBoxConfirm(
        `請確認要刪除${mtg.name}群組`,
      );
      if (ret === true) {
        try {
          await axios.delete(`/MonitorTypeGroup/${mtg._id}`);
          this.getMonitorTypeGroups();
        } catch (err) {
          throw new Error('Failed to delete mtg');
        }
      }
    },
  },
});
</script>

<style></style>
