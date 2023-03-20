<template>
  <div>
    <b-card header="RAW data" border-variant="success">
      <b-table :fields="fields" :items="items" striped hover></b-table>
    </b-card>
    <b-row>
      <b-col>
        <div id="trend"></div>
      </b-col>
    </b-row>
  </div>
</template>
<script lang="ts">
import Vue from 'vue';
import highcharts from 'highcharts';

export default Vue.extend({
  data() {
    let fields = [
      {
        key: 'CO2',
        label: 'CO2',
        sortable: true,
      },
      {
        key: 'N2',
        label: 'N2',
        sortable: true,
      },
      {
        key: 'CH4',
        label: 'CH4',
        sortable: true,
      },
      {
        key: 'O2+Ar',
        label: 'O2+Ar',
        sortable: true,
        formatter: (v: number) => v.toFixed(2),
      },
    ];
    let items = [
      {
        CO2: 2.51,
        N2: 80.31,
        CH4: 0,
        'O2+Ar': 17.23,
      },
      {
        CO2: 1.63,
        N2: 81.43,
        CH4: 0,
        'O2+Ar': 17.04,
      },
      {
        CO2: 5.8,
        N2: 79.14,
        CH4: 0,
        'O2+Ar': 15.16,
      },
      {
        CO2: 5.09,
        N2: 77.83,
        CH4: 0,
        'O2+Ar': 17.119999999999997,
      },
      {
        CO2: 2.4,
        N2: 81.06,
        CH4: 0,
        'O2+Ar': 16.58,
      },
      {
        CO2: 1.57,
        N2: 79.74,
        CH4: 0,
        'O2+Ar': 18.79,
      },
      {
        CO2: 2.25,
        N2: 78.76,
        CH4: 0,
        'O2+Ar': 19.099999999999998,
      },
      {
        CO2: 4.35,
        N2: 81.24,
        CH4: 0,
        'O2+Ar': 14.52,
      },
      {
        CO2: 2.47,
        N2: 80.01,
        CH4: 0,
        'O2+Ar': 17.63,
      },
      {
        CO2: 3.49,
        N2: 79.39,
        CH4: 0,
        'O2+Ar': 17.23,
      },
      {
        CO2: 4.83,
        N2: 78.16,
        CH4: 0,
        'O2+Ar': 17.12,
      },
      {
        CO2: 0.93,
        N2: 79.3,
        CH4: 0,
        'O2+Ar': 19.87,
      },
      {
        CO2: 3.86,
        N2: 79.04,
        CH4: 0,
        'O2+Ar': 17.21,
      },
      {
        CO2: 4.86,
        N2: 80.67,
        CH4: 0,
        'O2+Ar': 14.28,
      },
      {
        CO2: 3.64,
        N2: 79.06,
        CH4: 0,
        'O2+Ar': 17.400000000000002,
      },
      {
        CO2: 1.41,
        N2: 79.4,
        CH4: 0,
        'O2+Ar': 19.22,
      },
      {
        CO2: 3.93,
        N2: 78.22,
        CH4: 0,
        'O2+Ar': 17.96,
      },
      {
        CO2: 4.03,
        N2: 81.79,
        CH4: 0,
        'O2+Ar': 14.17,
      },
      {
        CO2: 2.14,
        N2: 78.98,
        CH4: 0,
        'O2+Ar': 18.990000000000002,
      },
      {
        CO2: 3.15,
        N2: 79.96,
        CH4: 0,
        'O2+Ar': 16.96,
      },
      {
        CO2: 3.75,
        N2: 78.58,
        CH4: 0,
        'O2+Ar': 17.78,
      },
      {
        CO2: 3.33,
        N2: 79.79,
        CH4: 0,
        'O2+Ar': 16.92,
      },
      {
        CO2: 3.25,
        N2: 79.63,
        CH4: 0,
        'O2+Ar': 17.2,
      },
      {
        CO2: 0.04,
        N2: 79.09,
        CH4: 0,
        'O2+Ar': 20.92,
      },
    ];
    return {
      fields,
      items,
    };
  },
  mounted(){
    this.drawBarChart1();
  },
  methods: {
    drawBarChart1() {
      let co2: highcharts.SeriesColumnOptions = {
        name: 'CO2',
        type: 'column',
        data: this.items.map(row => row.CO2),
      };
      let n2: highcharts.SeriesColumnOptions = {
        name: 'N2',
        type: 'column',
        data: this.items.map(row => row.N2),
      };
      let ch4: highcharts.SeriesColumnOptions = {
        name: 'CH4',
        type: 'column',
        data: this.items.map(row => row.CH4)
      };
      let O2Ar: highcharts.SeriesColumnOptions = {
        name: 'O2Ar',
        type: 'column',
        data: this.items.map(row => row['O2+Ar']),
      };
      let series = [co2, n2, ch4, O2Ar];
      let chartOpt: highcharts.Options = {
        chart: {
          type: 'column',
        },
        title: {
          text: '土壤氣體趨勢圖',
        },
        exporting: {
          enabled: false,
        },
        xAxis: {
          //categories: ['10分鐘累積', '1小時累積雨量', '日累積雨量'],
          crosshair: true,
        },
        yAxis: {
          min: 0,
          title: {
            text: 'ppm',
          },
        },
        tooltip: {
          headerFormat:
            '<span style="font-size:10px">{point.key}</span><table>',
          pointFormat:
            '<tr><td style="color:{series.color};padding:0">{series.name}: </td>' +
            '<td style="padding:0"><b>{point.y:.1f} ppm</b></td></tr>',
          footerFormat: '</table>',
          shared: true,
          useHTML: true,
        },
        plotOptions: {
          column: {
            pointPadding: 0.2,
            borderWidth: 0,
          },
        },
        series,
        credits: {
          enabled: false,
          href: 'http://www.wecc.com.tw/',
        },
      };
      highcharts.chart('trend', chartOpt);
    },
  },
});
</script>

<style></style>
