<template>
  <div>
    <b-card title="測點管理" class="text-center">
      <b-table
        responsive
        :fields="columns"
        :items="editMonitors"
        bordered
        sticky-header
        select-mode="single"
        style="min-height: 600px"
        selectable
        @row-selected="onInstSelected"
      >
        <template #thead-top
          ><b-tr>
            <b-td colspan="4">
              <b-button variant="gradient-success" @click="addMonitor()"
                >新增</b-button
              >
              <b-button
                class="ml-2"
                variant="gradient-danger"
                :disabled="!Boolean(selected)"
                @click="deleteMonitor()"
                >刪除</b-button
              >
              <b-button
                v-ripple.400="'rgba(255, 255, 255, 0.15)'"
                variant="primary"
                class="ml-2"
                @click="save"
              >
                儲存
              </b-button>
              <b-button
                v-ripple.400="'rgba(255, 255, 255, 0.15)'"
                variant="primary"
                class="ml-2"
                @click="select"
              >
                切換測量目標
              </b-button>
            </b-td></b-tr
          >
        </template>
        <template #cell(selected)="{ rowSelected }">
          <template v-if="rowSelected">
            <span aria-hidden="true">&check;</span>
            <span class="sr-only">Selected</span>
          </template>
          <template v-else>
            <span aria-hidden="true">&nbsp;</span>
            <span class="sr-only">Not selected</span>
          </template>
        </template>
        <template #cell(_id)="row">
          <b-form-input v-model="row.item._id" @change="markDirty(row.item)" />
        </template>
        <template #cell(desc)="row">
          <b-form-input v-model="row.item.desc" @change="markDirty(row.item)" />
        </template>
        <template #cell(measuring)="row">
          {{ activeID === row.item._id ? '是' : '否' }}
        </template>
        <template #cell(monitorTypes)="row">
          <v-select
            id="monitorType"
            v-model="row.item.monitorTypes"
            label="desp"
            :reduce="mt => mt._id"
            :options="monitorTypes"
            multiple
            @input="markDirty(row.item)"
          />
        </template>
      </b-table>
    </b-card>
  </div>
</template>
<script lang="ts">
import Vue from 'vue';
const Ripple = require('vue-ripple-directive');
import { mapActions, mapState } from 'vuex';
import { Monitor } from '../store/monitors/types';
import axios from 'axios';

interface EditMonitor extends Monitor {
  dirty: boolean;
}
export default Vue.extend({
  components: {},
  directives: {
    Ripple,
  },
  data() {
    let me = this;
    const columns = [
      {
        key: 'selected',
        label: '選擇',
      },
      {
        key: '_id',
        label: '代碼',
      },
      {
        key: 'desc',
        label: '名稱',
        sortable: true,
      },
      {
        key: 'measuring',
        label: '測量中',
      },
    ];

    let editMonitors = Array<EditMonitor>();
    let selected: EditMonitor | undefined;
    return {
      display: false,
      columns,
      editMonitors,
      selected,
    };
  },
  computed: {
    ...mapState('monitors', ['monitors', 'activeID']),
    ...mapState('monitorTypes', ['monitorTypes']),
  },
  async mounted() {
    await this.fetchMonitors();
    await this.fetchMonitorTypes();
    await this.getActiveID();
    this.copyMonitor();
  },
  methods: {
    ...mapActions('monitors', ['fetchMonitors', 'getActiveID', 'setActiveID']),
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    save() {
      const all = Array<any>();
      for (const m of this.editMonitors) {
        if (m.dirty) {
          all.push(axios.put(`/Monitor/${m._id}`, m));
        }
      }

      Promise.all(all).then(() => {
        this.fetchMonitors();
        this.$bvModal.msgBoxOk('成功');
      });
    },
    rollback() {
      this.fetchMonitors();
    },
    copyMonitor() {
      this.editMonitors.splice(0, this.editMonitors.length);
      for (let m of this.monitors) {
        this.editMonitors.push(Object.assign({ dirty: false }, m));
      }
    },
    async deleteMonitor() {
      if (this.selected === undefined) return;

      if (this.editMonitors.length === 1) {
        this.$bvModal.msgBoxOk('應至少有一個測點');
        return;
      }

      const confirm = await this.$bvModal.msgBoxConfirm(
        `確定要刪除${this.selected._id}?`,
        { okTitle: '確認', cancelTitle: '取消' },
      );

      if (!confirm) return;

      const _id = this.selected._id;
      const res = await axios.delete(`/Monitor/${_id}`);
      if (res.status == 200) this.$bvModal.msgBoxOk('成功');
      await this.fetchMonitors();
      this.copyMonitor();
    },
    addMonitor() {
      this.editMonitors.push({
        _id: `me${this.editMonitors.length}`,
        desc: '',
        dirty: true,
      });
    },
    markDirty(item: any) {
      item.dirty = true;
    },
    onInstSelected(items: Array<EditMonitor>) {
      this.selected = items[0];
    },
    select() {
      this.setActiveID(this.selected?._id);
    },
  },
});
</script>

<style></style>
