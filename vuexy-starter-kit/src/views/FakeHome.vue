<template>
  <b-row class="match-height">
    <b-col lg="9" md="12">
      <b-card title="即時影像">
        <b-img src="../assets/images/scotter.png" fluid-grow />
      </b-card>
      <b-card title="5分鐘噪音歷線圖"
        ><b-img src="../assets/images/histogram.png" fluid height="h-10"
      /></b-card>
    </b-col>
    <b-col lg="3" md="12">
      <b-card title="時間">
        <b-card-text>2021/2/25 10:00:00</b-card-text>
      </b-card>
      <b-card title="噪音監控">
        <b-card-text>即時值: 75dB</b-card-text>
        <b-card-text>最大噪音: 90dB</b-card-text>
        <b-card-text>背景噪音: 20dB</b-card-text>
      </b-card>
      <b-card title="車牌辨識">
        <b-card-text>ABC-1234</b-card-text>
      </b-card>
      <b-card title="氣象">
        <b-card-text>風速: 1 m/s</b-card-text>
        <b-card-text>風向: 東北東</b-card-text>
        <b-card-text>溫度: 25度</b-card-text>
        <b-card-text>濕度: 75%</b-card-text>
        <b-card-text>大氣壓力: 1pa</b-card-text>
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
    return {};
  },
  computed: {},
  mounted() {},
  beforeDestroy() {},
  methods: {},
};
</script>

<style></style>
