<template>
  <b-row class="match-height">
    <b-col lg="12" md="12">
      <b-card title="最新監測資訊">
        <b-table striped hover :fields="columns" :items="rows" show-empty>
          <template #thead-top>
            <b-tr>
              <b-th></b-th>
              <b-th
                v-for="mt in userInfo.monitorTypeOfInterest"
                :key="mt"
                :colspan="form.monitors.length"
                class="text-center"
                style="text-transform: none"
                >{{ getMtDesc(mt) }}</b-th
              >
            </b-tr>
          </template>
        </b-table>
      </b-card>
    </b-col>
  </b-row>
</template>
<style scoped>
.legend {
  /* min-width: 100px;*/
  background-color: white;
}

.airgreen div:before {
  background: #009865;
  background-color: rgb(0, 152, 101);
}

.airgreen {
  background-color: rgb(229, 244, 239);
}
</style>
<script>
import moment from 'moment';
import { mapActions, mapState, mapGetters } from 'vuex';
import axios from 'axios';
export default {
  data() {
    const range = [moment().subtract(1, 'days').valueOf(), moment().valueOf()];
    return {
      dataTypes: [
        // { txt: '小時資料', id: 'hour' },
        { txt: '分鐘資料', id: 'min' },
        // { txt: '秒資料', id: 'second' },
      ],
      form: {
        monitors: [],
        dataType: 'min',
        range,
      },
      columns: [],
      rows: [],
      realTimeStatus: [],
      spray: false,
      spray_connected: false,
      loader: undefined,
      timer: 0,
      refreshTimer: 0,
      infoWindowPos: null,
      infoWinOpen: false,
      currentMidx: null,

      infoOptions: {
        content: '',
        //optional: offset infowindow so it visually sits nicely on top of our marker
        pixelOffset: {
          width: 0,
          height: -35,
        },
      },
    };
  },
  computed: {
    ...mapState('monitorTypes', ['monitorTypes']),
    ...mapState('monitors', ['monitors']),
    ...mapState('user', ['userInfo']),
    ...mapGetters('monitorTypes', ['mtMap']),
    ...mapGetters('monitors', ['mMap']),
  },
  mounted() {
    this.refresh();
    this.refreshTimer = setInterval(() => {
      this.refresh();
    }, 30000);
  },
  beforeDestroy() {
    clearInterval(this.timer);
    clearInterval(this.refreshTimer);
  },
  methods: {
    ...mapActions('monitorTypes', ['fetchMonitorTypes']),
    ...mapActions('monitors', ['fetchMonitors']),
    async refresh() {
      await this.fetchMonitorTypes();
      if (this.monitorTypes.length !== 0) {
        this.form.monitorTypes = [];
        this.form.monitorTypes.push(this.monitorTypes[0]._id);
      }

      await this.fetchMonitors();
      if (this.monitors.length !== 0) {
        this.form.monitors = [];
        for (const m of this.monitors) this.form.monitors.push(m._id);
      }

      this.query();
      this.getRealtimeStatus();
    },
    async query() {
      this.rows.splice(0, this.rows.length);
      this.columns = this.getColumns();
      const monitors = this.form.monitors.join(':');
      const monitorTypes = this.userInfo.monitorTypeOfInterest.join(':');
      const url = `/LatestData/${monitors}/${monitorTypes}/${this.form.dataType}`;

      const ret = await axios.get(url);
      for (const row of ret.data.rows) {
        row.date = moment(row.date).format('MM-DD HH:mm');
      }

      this.rows = ret.data.rows;
    },
    async getRealtimeStatus() {
      const ret = await axios.get('/RealtimeStatus');
      this.realTimeStatus = ret.data;
    },
    cellDataTd(i) {
      return (_value, _key, item) => item.cellData[i].cellClassName;
    },
    getMtDesc(mt) {
      const mtCase = this.mtMap.get(mt);
      return `${mtCase.desp}(${mtCase.unit})`;
    },
    getColumns() {
      const ret = [];
      ret.push({
        key: 'date',
        label: '時間',
      });
      let i = 0;
      for (const mt of this.userInfo.monitorTypeOfInterest) {
        const mtCase = this.mtMap.get(mt);
        for (const m of this.form.monitors) {
          // emtpyCell  ${mtCase.desp}(${mtCase.unit})
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
  },
};
</script>

<style></style>
