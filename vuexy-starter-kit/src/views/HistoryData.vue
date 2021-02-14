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
                format="YYYY-MM-DD HH:mm"
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
      <b-table striped hover :fields="columns" :items="rows" show-empty />
    </b-card>
  </div>
</template>
<style lang="scss">
@import '@core/scss/vue/libs/vue-select.scss';
</style>
<script lang="ts">
import Vue from 'vue';
import vSelect from 'vue-select';
import DatePicker from 'vue2-datepicker';
import 'vue2-datepicker/index.css';
import 'vue2-datepicker/locale/zh-tw';
import Ripple from 'vue-ripple-directive';
import { mapState, mapGetters } from 'vuex';
import moment from 'moment';
import axios from 'axios';

export default Vue.extend({
  components: {
    vSelect,
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
        { txt: '秒資料', id: 'second' },
      ],
      form: {
        monitorTypes: [],
        dataType: 'hour',
        range,
      },
      display: false,
      columns: [],
      rows: [],
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapGetters('monitorTypes', ['mtMap']),
  },
  mounted() {
    if (this.monitorTypes.length !== 0) {
      // eslint-disable-next-line no-underscore-dangle
      this.form.monitorTypes.push(this.monitorTypes[0]._id);
    }
  },
  methods: {
    async query() {
      this.display = true;
      this.rows = [];
      this.columns = this.getColumns();
      const url = `/HistoryReport/${this.form.monitorTypes.join(':')}/${
        this.form.dataType
      }/${this.form.range[0]}/${this.form.range[1]}`;
      const ret = await axios.get(url);
      for (const row of ret.data.rows) {
        row.date = moment(row.date).format('lll');
      }

      this.rows = ret.data.rows;
      // console.log(this.rows);
    },
    cellDataTd(i) {
      return (_value, _key, item) => item.cellData[i].cellClassName;
    },
    getColumns() {
      const ret = [];
      ret.push({
        key: 'date',
        label: '時間',
      });
      for (let i = 0; i < this.form.monitorTypes.length; i += 1) {
        const mtCase = this.mtMap.get(this.form.monitorTypes[i]);
        ret.push({
          key: `cellData[${i}].v`,
          label: `${mtCase.desp}(${mtCase.unit})`,
          tdClass: this.cellDataTd(i),
        });
      }
      return ret;
    },
  },
});
</script>

<style></style>
