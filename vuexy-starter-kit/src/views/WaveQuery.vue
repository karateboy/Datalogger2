<template>
  <div>
    <b-card>
      <b-form @submit.prevent>
        <b-row>
          <b-col cols="12">
            <b-form-group
              label="查詢日期"
              label-for="dataRange"
              label-cols-md="3"
            >
              <date-picker
                id="dataRange"
                v-model="form.date"
                :type="pickerType"
                value-type="timestamp"
                :show-second="false"
              />
            </b-form-group>
          </b-col>
        </b-row>
        <b-row>
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
          </b-col>
        </b-row>
      </b-form>
    </b-card>
    <b-card v-if="display">
      <b-row>
        <b-col><b-img :src="getWaveBEImgUrl" fluid /></b-col>
        <b-col><b-img :src="getWaveBNImgUrl" fluid /></b-col>
        <b-col><b-img :src="getWaveBZImgUrl" fluid /></b-col>
      </b-row>
      <b-row>
        <b-col><b-img :src="getWaveDEImgUrl" fluid /></b-col>
        <b-col><b-img :src="getWaveDNImgUrl" fluid /></b-col>
        <b-col><b-img :src="getWaveDZImgUrl" fluid /></b-col>
      </b-row>
    </b-card>
  </div>
</template>
<script lang="ts">
import Vue from 'vue';
import DatePicker from 'vue2-datepicker';
import 'vue2-datepicker/index.css';
import 'vue2-datepicker/locale/zh-tw';
const Ripple = require('vue-ripple-directive');
import { DisplayReport, RowData } from './types';
import moment from 'moment';
import axios from 'axios';
const excel = require('../libs/excel');
const _ = require('lodash');

interface RowDataReport extends RowData {
  time?: string;
}
export default Vue.extend({
  components: {
    DatePicker,
  },
  directives: {
    Ripple,
  },
  data() {
    const date = moment().valueOf();
    return {
      display: false,
      form: {
        date,
        reportType: 'daily',
      },
      activeDateTime: 0,
    };
  },
  computed: {
    pickerType() {
      if (this.form.reportType === 'daily') return 'date';
      return 'month';
    },
    baseUrl(): string {
      return process.env.NODE_ENV === 'development'
        ? 'http://localhost:9000/'
        : '/';
    },
    getWaveBEImgUrl(): string {
      return `${this.baseUrl}WaveBE?dateTime=${this.activeDateTime}`;
    },
    getWaveBNImgUrl(): string {
      return `${this.baseUrl}WaveBN?dateTime=${this.activeDateTime}`;
    },
    getWaveBZImgUrl(): string {
      return `${this.baseUrl}WaveBZ?dateTime=${this.activeDateTime}`;
    },
    getWaveDEImgUrl(): string {
      return `${this.baseUrl}WaveDE?dateTime=${this.activeDateTime}`;
    },
    getWaveDNImgUrl(): string {
      return `${this.baseUrl}WaveDN?dateTime=${this.activeDateTime}`;
    },
    getWaveDZImgUrl(): string {
      return `${this.baseUrl}WaveDZ?dateTime=${this.activeDateTime}`;
    },
  },
  methods: {
    async query() {
      this.display = true;
      this.activeDateTime = this.form.date;
    },
  },
});
</script>
