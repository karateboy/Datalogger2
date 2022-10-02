<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="12">
            <b-form-group label="測點" label-for="monitor" label-cols-md="3">
              <v-select
                id="monitor"
                v-model="form.monitor"
                label="desc"
                :reduce="mt => mt._id"
                :options="monitorOfNoEPA"
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group
              label="資訊完整度"
              label-for="alarmLevel"
              label-cols-md="3"
            >
              <v-select
                id="alarmLevel"
                v-model="form.respType"
                label="txt"
                :reduce="dt => dt.id"
                :options="respTypes"
              />
            </b-form-group>
          </b-col>
          <b-col cols="12">
            <b-form-group label="資料區間" label-for="start" label-cols-md="3">
              <date-picker
                id="start"
                v-model="form.start"
                type="datetime"
                format="YYYY-MM-DD HH:mm"
                value-type="timestamp"
                :show-second="false"
              />
            </b-form-group>
          </b-col>
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

    <b-table striped hover :fields="columns" :items="rows" small responsive />
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
const Ripple = require('vue-ripple-directive');
import { mapActions, mapGetters, mapMutations } from 'vuex';
import moment from 'moment';
import axios from 'axios';
interface AisDataResp {
  columns: Array<string>;
  ships: Array<any>;
}
export default Vue.extend({
  components: {
    DatePicker,
    vSelect,
  },
  directives: {
    Ripple,
  },
  data() {
    let me: any = this;
    let columns = Array<string>();
    let rows = Array<any>();
    return {
      display: false,
      respTypes: [
        { id: 'full', txt: '完整' },
        { id: 'simple', txt: '簡易' },
      ],
      columns,
      rows,
      form: {
        monitor: '',
        start: new Date().getTime(),
        respType: 'full',
      },
    };
  },
  computed: {
    ...mapGetters('monitors', ['mMap', 'monitorOfNoEPA']),
  },
  async mounted() {
    await this.fetchMonitors();
    if (this.monitorOfNoEPA.length !== 0)
      this.form.monitor = this.monitorOfNoEPA[0]._id;
  },
  methods: {
    ...mapActions('monitors', ['fetchMonitors']),
    ...mapMutations(['setLoading']),
    async query() {
      this.display = true;
      const url = `/NearestAisDataInThePast/${this.form.monitor}/${this.form.respType}/${this.form.start}`;

      try {
        const res = await axios.get(url);
        if (res.status === 200) {
          let aisDataResp = res.data as AisDataResp;
          this.columns = aisDataResp.columns;
          this.rows = aisDataResp.ships;
        }
      } catch (err) {
        console.error(`${err}`);
      }
    },
  },
});
</script>

<style></style>
