<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="12">
            <b-form-group
              label="測項"
              label-for="monitorType"
              label-cols-md="3"
            >
              <v-select
                id="monitorType"
                v-model="form.monitorTypes"
                label="desp"
                :reduce="mt => mt._id"
                :options="monitorTypes"
                multiple
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group
              label="資料來源"
              label-for="dataType"
              label-cols-md="3"
            >
              <v-select
                id="dataType"
                v-model="form.dataType"
                label="txt"
                :reduce="dt => dt.id"
                :options="dataTypes"
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group
              label="資料區間"
              label-for="dataRange"
              label-cols-md="3"
            >
              <date-picker
                id="dataRange"
                v-model="form.range"
                :range="true"
                type="datetime"
                format="YYYY-MM-DD HH:00"
                value-type="timestamp"
                :show-second="false"
              />
            </b-form-group>
          </b-col>
          <!-- submit and reset -->
          <b-col offset-md="3">
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              type="submit"
              variant="primary"
              class="mr-1"
              @click="query"
            >
              查詢
            </b-button>
            <b-button
              v-ripple.400="'rgba(186, 191, 199, 0.15)'"
              type="reset"
              variant="outline-secondary"
            >
              取消
            </b-button>
          </b-col>
        </b-row>
      </b-form>
    </b-card>
    <b-card v-show="display">
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="12">
            <b-form-group label="註記理由" label-for="reason" label-cols-md="3">
              <b-form-input v-model="form2.reason" />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group
              label="註記代碼"
              label-for="statusCode"
              label-cols-md="3"
            >
              <v-select
                id="statusCode"
                v-model="form2.statusCode"
                label="txt"
                :reduce="dt => dt.id"
                :options="statusCodes"
              />
            </b-form-group>
          </b-col>
          <!-- submit and reset -->
          <b-col offset-md="3">
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              type="submit"
              variant="primary"
              class="mr-1"
              :disabled="!canAudit"
              @click="audit"
            >
              註記
            </b-button>
          </b-col>
        </b-row>
      </b-form>
      <br />
      <b-table
        striped
        hover
        :fields="columns"
        :items="rows"
        show-empty
        :per-page="15"
        :current-page="currentPage"
        responsive
        sticky-header="800px"
      >
        <template #thead-top>
          <b-tr>
            <b-th
              ><b-button variant="info" @click="selectAll">全選</b-button></b-th
            >
            <b-th></b-th>
            <b-th
              v-for="mt in form.monitorTypes"
              :key="mt"
              :colspan="form.monitors.length"
              class="text-center"
              style="text-transform: none"
              >{{ getMtDesc(mt) }}</b-th
            >
          </b-tr>
        </template>
        <template #cell(include)="data">
          <b-form-checkbox v-model="data.item.include" />
        </template>
      </b-table>
      <b-pagination
        v-model="currentPage"
        :total-rows="rows.length"
        :per-page="15"
        first-text="⏮"
        prev-text="⏪"
        next-text="⏩"
        last-text="⏭"
        class="mt-4"
      ></b-pagination>
    </b-card>
  </div>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script lang="ts">
import Vue from 'vue';
import DatePicker from 'vue2-datepicker';
import 'vue2-datepicker/index.css';
import 'vue2-datepicker/locale/zh-tw';
const Ripple = require('vue-ripple-directive');
import { mapState, mapGetters, mapActions, mapMutations } from 'vuex';
import moment from 'moment';
import axios from 'axios';

export default Vue.extend({
  components: {
    DatePicker,
  },
  directives: {
    Ripple,
  },

  data() {
    const range = [moment().subtract(1, 'days').valueOf(), moment().valueOf()];
    return {
      dataTypes: [
        { txt: '小時資料', id: 'hour' },
        { txt: '分鐘資料', id: 'min' },
      ],
      form: {
        monitors: Array<any>(),
        monitorTypes: Array<any>(),
        dataType: 'hour',
        range,
      },
      form2: {
        statusCode: '0',
        reason: '',
      },
      statusCodes: [
        {
          id: '0',
          txt: '復原註記',
        },
        {
          id: 'm',
          txt: '人工註記:有效資料',
        },
        {
          id: 'M',
          txt: '人工註記:無效資料',
        },
      ],
      display: false,
      columns: Array<any>(),
      rows: Array<any>(),
      currentPage: 1,
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapState('monitors', ['monitors']),
    ...mapGetters('monitorTypes', ['mtMap']),
    ...mapGetters('monitors', ['mMap']),
    resultTitle(): string {
      return `總共${this.rows.length}筆`;
    },

    canAudit() {
      let auditCount = 0;
      for (const item of this.rows) {
        if (item.include) {
          auditCount++;
        }
      }
      if (auditCount === 0) return false;

      if (this.form2.reason === '' && this.form2.statusCode !== '0') {
        return false;
      }

      return true;
    },
  },
  async mounted() {
    await this.fetchMonitorTypes();
    await this.fetchMonitors();

    if (this.monitors.length !== 0) {
      this.form.monitors.push(this.monitors[0]._id);
    }

    if (this.monitorTypes.length !== 0) {
      this.form.monitorTypes.push('PM25');
    }
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors']),
    ...mapMutations(['setLoading']),
    async query() {
      this.setLoading({ loading: true });
      this.display = true;
      this.rows = [];
      this.columns = this.getColumns();
      const monitors = this.form.monitors.join(':');
      const monitorTypes = this.form.monitorTypes.join(':');
      const url = `/HistoryReport/${monitors}/${monitorTypes}/${this.form.dataType}/${this.form.range[0]}/${this.form.range[1]}`;

      const ret = await axios.get(url);
      this.setLoading({ loading: false });
      this.rows.splice(0, this.rows.length);
      for (const row of ret.data.rows) {
        row.dateStr = moment(row.date).format('lll');
        row.include = false;
        this.rows.push(row);
      }
    },
    cellDataTd(i: number) {
      return (_value: any, _key: any, item: any) =>
        item.cellData[i].cellClassName;
    },
    getMtDesc(mt: string) {
      const mtCase = this.mtMap.get(mt);
      return `${mtCase.desp}(${mtCase.unit})`;
    },
    getColumns() {
      const ret = [];
      ret.push({
        key: 'include',
        label: '選擇',
      });
      ret.push({
        key: 'dateStr',
        label: '時間',
      });
      let i = 0;
      for (const mt of this.form.monitorTypes) {
        for (const m of this.form.monitors) {
          const mCase = this.mMap.get(m);
          ret.push({
            key: `cellData[${i}].v`,
            label: `${mCase.desc}`,
            tdClass: this.cellDataTd(i),
          });
          i++;
        }
      }

      return ret;
    },
    audit() {
      // case class ManualAuditParam(reason: String, updateList: Seq[UpdateRecordParam])
      // case class UpdateRecordParam(time: Long, mt:String, status: String)
      const updateList = [];
      for (const item of this.rows) {
        if (item.include) {
          for (let i = 0; i < item.cellData.length; i++) {
            const cellData = item.cellData[i];
            if (cellData.v !== '-') {
              const status = this.form2.statusCode + cellData.status.substr(1);
              updateList.push({
                time: item.date,
                mt: this.form.monitorTypes[i],
                status,
              });
            }
          }
        }
      }
      const param = {
        reason: this.form2.reason,
        updateList,
      };
      axios.put(`/Record/${this.form.dataType}`, param).then(res => {
        const ret = res.data;
        if (ret.ok) {
          this.$bvModal.msgBoxOk('成功');
          this.query();
        }
      });
    },
    canInclude(item: any) {
      for (const cellData of item.cellData) {
        if (cellData.v == '-') return false;
      }

      return true;
    },
    selectAll() {
      for (let item of this.rows) {
        item.include = !item.include;
      }
    },
  },
});
</script>
