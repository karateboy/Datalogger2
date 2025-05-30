<template>
  <div v-if="report">
    <b-card
      :header="title"
      header-class="h4 display text-center"
      border-variant="primary"
      header-bg-variant="primary"
      header-text-variant="white"
      class="text-center"
      no-body
    >
      <b-table
        :fields="fields"
        :items="report.aqi.subExplain"
        bordered
        hover
        striped
      >
        <template #thead-top>
          <b-tr>
            <b-td colspan="3" :class="report.aqi.value.css">
              <h1 class="text-center">AQI:&nbsp;{{ report.aqi.value.aqi }}</h1>
            </b-td>
          </b-tr>
        </template>
      </b-table>
    </b-card>
    <b-card title="即時空氣品質指標(AQI)計算方式如下">
      <p>
        各測項即時濃度依下列公式計算後，再對應下表得出O<sub>3</sub>、PM<sub>2.5</sub>、PM<sub>10</sub>、CO、SO<sub>2</sub>、
        NO<sub>2</sub>等6個測項之即時副指標值，再取出其中最大值為即時空氣品質指標，該最大值測項即為指標污染物：
      </p>
      <table summary="for layout" border="0" style="margin-left: 2em">
        <tbody>
          <tr>
            <td>O<sub>3,8hr</sub></td>
            <td>：</td>
            <td>
              取最近連續8小時移動平均值
              (例如今日上午10點發布的O<sub>3</sub>的8小時濃度平均值，是取今日上午2點至上午9點監測數據的平均值。）
            </td>
          </tr>
          <tr>
            <td>O<sub>3</sub></td>
            <td>：</td>
            <td>取即時濃度值</td>
          </tr>
          <tr>
            <td>PM<sub>2.5</sub></td>
            <td>：</td>
            <td>
              0.5 × 前12小時平均 + 0.5 × 前4小時平均
              (前4小時2筆有效，前12小時6筆有效）
            </td>
          </tr>
          <tr>
            <td>PM<sub>10</sub></td>
            <td>：</td>
            <td>
              0.5 × 前12小時平均 + 0.5 × 前4小時平均
              (前4小時2筆有效，前12小時6筆有效）
            </td>
          </tr>
          <tr>
            <td>CO</td>
            <td>：</td>
            <td>
              取最近連續8小時移動平均值
              (例如今日上午10點發布的CO的8小時濃度平均值，是取今日上午2點至上午9點監測數據的平均值。）
            </td>
          </tr>
          <tr>
            <td>SO<sub>2</sub></td>
            <td>：</td>
            <td>取即時濃度值</td>
          </tr>
          <tr>
            <td>SO<sub>2,24hr</sub></td>
            <td>：</td>
            <td>
              取最近連續24小時濃度平均值
              (例如今日上午10點發布的SO<sub>2</sub>的24小時濃度平均值，是取前1天上午10點至今日上午9點監測數據的平均值。）
            </td>
          </tr>
          <tr>
            <td>NO<sub>2</sub></td>
            <td>：</td>
            <td>取即時濃度值</td>
          </tr>
        </tbody>
      </table>
      <p>
        PM<sub>2.5</sub>移動平均值有效位數為小數點以下第1位，會取到小數點以下第2位採四捨五入方式進位，O<sub>3</sub>的8小時移動平均值及PM<sub>10</sub>移動平均值有效位數為整數位，會取到小數點以下第1位，採四捨五入方式進位。
      </p>
      <p>　</p>
      <h2 style="margin-bottom: 8px">污染物濃度與即時副指標值對照表</h2>
      <table
        border="1"
        style="margin: 0 auto"
        class="DIV_CENTER ALT_TABLE_EVENT TMP1"
      >
        <tbody>
          <tr class="ALT">
            <th nowrap="">污染物</th>
            <th nowrap="">O<sub>3,8hr</sub></th>
            <th nowrap="">
              O<sub>3</sub><sup><font color="#FF0000">(1)</font></sup>
            </th>
            <th nowrap="">PM<sub>2.5</sub></th>
            <th nowrap="">PM<sub>10</sub></th>
            <th nowrap="">CO</th>
            <th nowrap="">SO<sub>2</sub></th>
            <th nowrap="">NO<sub>2</sub></th>
          </tr>
          <tr>
            <th nowrap="">即時統計</th>
            <td class="text-center">
              最近連續<br />
              8小時移動<br />
              平均值
            </td>
            <td class="text-center">
              即時<br />
              濃度值
            </td>
            <td class="text-center">
              0.5 ×前12小時平均<br />+ <br />
              0.5 × 前4小時平均
            </td>
            <td class="text-center">
              0.5 ×前12小時平均<br />+ <br />
              0.5 × 前4小時平均
            </td>
            <td class="text-center">
              最近連續<br />
              8小時移動<br />
              平均值
            </td>
            <td class="text-center">
              即時<br />
              濃度值
            </td>
            <td class="text-center">
              即時<br />
              濃度值
            </td>
          </tr>
          <tr class="ALT">
            <th nowrap="">單位</th>
            <td class="text-center">ppm</td>
            <td class="text-center">ppm</td>
            <td class="text-center">μg/m<sup>3</sup></td>
            <td class="text-center">μg/m<sup>3</sup></td>
            <td class="text-center">ppm</td>
            <td class="text-center">ppb</td>
            <td class="text-center">ppb</td>
          </tr>
          <tr>
            <th nowrap="">AQI值</th>
            <th nowrap="" colspan="7">　</th>
          </tr>
          <tr class="ALT">
            <td class="AQI1 text-center">0～50</td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              0.000 - 0.054
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">-</td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              0.0 - 12.4
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              0 - 30
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              0 - 4.4
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">0 - 8</td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              0 - 21
            </td>
          </tr>
          <tr>
            <td class="AQI2 text-center">51～100</td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              0.055 - 0.070
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">-</td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              12.5 - 30.4
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              31 - 75
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              4.5 - 9.4
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              9 - 65
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              22 - 100
            </td>
          </tr>
          <tr class="ALT">
            <td class="AQI3 text-center">101～150</td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              0.071 - 0.085
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              0.101 - 0.134
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              30.5 - 50.4
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              76 - 190
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              9.5 - 12.4
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              66 - 160
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              101 - 360
            </td>
          </tr>
          <tr>
            <td class="AQI4 text-center">151～200</td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              0.086 - 0.105
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              0.135 - 0.204
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              50.5 - 125.4
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              191 - 354
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              12.5 - 15.4
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              161 - 304<sup class="text-danger">(3)</sup>
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              361 - 649
            </td>
          </tr>
          <tr class="ALT">
            <td class="AQI5 text-center">201～300</td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              0.106 - 0.200
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              0.205 - 0.404
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              125.5 - 225.4
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              355 - 424
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              15.5 - 30.4
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              305 - 604<sup class="text-danger">(3)</sup>
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              650 - 1249
            </td>
          </tr>
          <tr>
            <td class="AQI6 text-center">301～400</td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              <sup class="text-danger">(2)</sup>
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              0.405 - 0.504
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              225.5 - 325.4
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              425 - 504
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              30.5 - 40.4
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              605 - 804<sup class="text-danger">(3)</sup>
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              1250 - 1649
            </td>
          </tr>
          <tr class="ALT">
            <td class="AQI6 text-center">401～500</td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              <sup class="text-danger">(2)</sup>
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              0.505 - 0.604
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              325.5 - 500.4
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              505 - 604
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              40.5 - 50.4
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              805 -1004<sup class="text-danger">(3)</sup>
            </td>
            <td class="text-center" style="font-size: 10pt" nowrap="">
              1650 - 2049
            </td>
          </tr>
        </tbody>
      </table>
      <ol>
        <li>
          一般以臭氧(O<sub>3</sub>)8小時值計算各地區之空氣品質指標(AQI)。但部分地區以臭氧(O<sub>3</sub>)小時值計算空氣品質指標(AQI)是更具有預警性，在此情況下，臭氧(O<sub>3</sub>)8小時與臭氧(O<sub>3</sub>)1小時之空氣品質指標(AQI)則皆計算之，取兩者之最大值作為空氣品質指標(AQI)。
        </li>
        <li>
          空氣品質指標(AQI)301以上之指標值，是以臭氧(O<sub>3</sub>)小時值計算之，不以臭氧(O<sub>3</sub>)8小時值計算之。
        </li>
        <li>
          空氣品質指標(AQI)200以上之指標值，是以二氧化硫(SO<sub>2</sub>)24小時值計算之，不以二氧化硫(SO<sub>2</sub>)小時值計算之。<br />
          　
        </li>
      </ol>
    </b-card>
  </div>
</template>
<style>
table.TMP1 td {
  font-size: 12pt;
  padding: 3px;
  vertical-align: middle;
}
</style>
<script lang="ts">
import Vue from 'vue';
import axios from 'axios';
import moment from 'moment';

interface RealtimeAQI {
  date: Date;
  aqi: AqiExplainReport;
}

interface AqiExplain {
  aqi: string;
  value: string;
  css: string;
}

interface AqiSubExplain {
  mtName: string;
  explain: AqiExplain;
}

interface AqiExplainReport {
  value: AqiExplain;
  subExplain: Array<AqiSubExplain>;
}
export default Vue.extend({
  data() {
    let report: RealtimeAQI | undefined;
    const fields = [
      {
        key: 'mtName',
        label: '測項',
        sortable: true,
        tdClass: function (v: string, _key: string, item: AqiSubExplain) {
          return [item.explain.css];
        },
      },
      {
        key: 'explain.aqi',
        label: '副指標',
        sortable: true,
        tdClass: function (v: string, _key: string, item: AqiSubExplain) {
          return [item.explain.css];
        },
      },
      {
        key: 'explain.value',
        label: '測值',
        sortable: true,
        tdClass: function (v: string, _key: string, item: AqiSubExplain) {
          return [item.explain.css];
        },
      },
    ];
    return {
      report,
      fields,
      timer: 0,
    };
  },
  computed: {
    title(): string {
      if (this.report) {
        let lastHour = moment(this.report.date);
        return `即時AQI (${lastHour.format('lll')})`;
      }

      return '';
    },
  },
  mounted() {
    this.getRealtimeAQI();
    this.timer = setInterval(this.getRealtimeAQI, 60000);
  },
  beforeDestroy() {
    clearInterval(this.timer);
  },
  methods: {
    async getRealtimeAQI() {
      const res = await axios.get('/RealtimeAQI');
      if (res.status === 200) {
        this.report = res.data as RealtimeAQI;
      }
    },
  },
});
</script>
