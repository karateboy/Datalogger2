<template>
  <div>
    <form-wizard
      color="#7367F0"
      :title="null"
      :subtitle="null"
      layout="vertical"
      finish-button-text="確認"
      back-button-text="向前"
      next-button-text="向後"
      class="wizard-vertical vertical-steps steps-transparent"
      @on-complete="formSubmitted"
    >
      <!-- account datails tab -->
      <tab-content title="儀器種類" :before-change="validateInstType">
        <validation-observer ref="instTypeRules" tag="form">
          <b-row>
            <b-col cols="12">
              <b-form-group
                label="儀器種類"
                label-for="instrumentType"
                label-cols-md="3"
              >
                <validation-provider
                  v-slot="{ errors }"
                  name="儀器種類"
                  rules="required"
                >
                  <v-select
                    id="instrumentType"
                    v-model="form.instType"
                    label="desp"
                    :reduce="inst => inst.id"
                    :options="instrumentTypes"
                  />
                  <small class="text-danger">{{ errors[0] }}</small>
                </validation-provider>
              </b-form-group>
            </b-col>
            <b-col cols="12">
              <b-form-group
                label="儀器ID"
                label-for="inst-id"
                label-cols-md="3"
              >
                <validation-provider
                  v-slot="{ errors }"
                  name="儀器ID"
                  rules="required"
                >
                  <b-form-input id="inst-id" v-model="form._id" />
                  <small class="text-danger">{{ errors[0] }}</small>
                </validation-provider>
              </b-form-group>
            </b-col>
          </b-row>
        </validation-observer>
      </tab-content>

      <!-- personal info tab -->
      <tab-content title="通訊協定" :before-change="validateProtocol">
        <validation-observer ref="protocolRules" tag="form">
          <b-row>
            <b-col cols="12">
              <b-form-group
                label="通訊協定"
                label-for="protocol"
                label-cols-md="3"
              >
                <validation-provider
                  v-slot="{ errors }"
                  name="通訊協定"
                  rules="required"
                >
                  <v-select
                    v-model="form.protocol.protocol"
                    label="desp"
                    :reduce="p => p.id"
                    :options="getProtocolOptions()"
                  />
                  <small class="text-danger">{{ errors[0] }}</small>
                </validation-provider>
              </b-form-group>
            </b-col>
            <b-col v-if="form.protocol.protocol === 'tcp'" cols="12">
              <b-form-group label="網址" label-for="host" label-cols-md="3">
                <validation-provider
                  v-slot="{ errors }"
                  name="網址"
                  rules="required"
                >
                  <b-form-input
                    id="host"
                    v-model="form.protocol.host"
                    placeholder="localhost"
                  />
                  <small class="text-danger">{{ errors[0] }}</small>
                </validation-provider>
              </b-form-group>
            </b-col>
            <b-col v-if="form.protocol.protocol === 'serial'" cols="12">
              <b-form-group
                label="COM Port"
                label-for="com-port"
                label-cols-md="3"
              >
                <validation-provider
                  v-slot="{ errors }"
                  name="通訊埠"
                  rules="required"
                >
                  <b-form-spinbutton
                    v-model="form.protocol.comPort"
                    min="1"
                    max="100"
                  ></b-form-spinbutton>
                  <small class="text-danger">{{ errors[0] }}</small>
                </validation-provider>
              </b-form-group>
            </b-col>
          </b-row>
        </validation-observer>
      </tab-content>

      <!-- address -->
      <tab-content
        v-if="hasDetailConfig"
        title="細部設定"
        :before-change="validateDetailConfig"
      >
        <validation-observer ref="detailConfigRules">
          <tapi-config-page
            v-if="isTapiInstrument"
            :inst-type="form.instType"
            :param-str="form.param"
            @param-changed="onParamChange"
          />
          <div v-else>TBD {{ form.instType }}</div>
        </validation-observer>
      </tab-content>

      <!-- social link -->
      <tab-content title="設定摘要">
        <h3>儀器設定摘要</h3>
        <p>
          <b-form-textarea
            rows="10"
            readonly
            :value="instrumentSummary"
          ></b-form-textarea>
        </p>
      </tab-content>
    </form-wizard>
  </div>
</template>

<script>
import axios from 'axios';
import { FormWizard, TabContent } from 'vue-form-wizard';
import { ValidationObserver } from 'vee-validate';
import vSelect from 'vue-select';
import { required, email } from '@validations';
import 'vue-form-wizard/dist/vue-form-wizard.min.css';
import ToastificationContent from '@core/components/toastification/ToastificationContent.vue';
import TapiConfigPage from './TapiConfigPage.vue';

export default {
  components: {
    FormWizard,
    TabContent,
    vSelect,
    // eslint-disable-next-line vue/no-unused-components
    ToastificationContent,
    ValidationObserver,
    TapiConfigPage,
  },
  data() {
    return {
      form: {
        _id: '',
        instType: '',
        protocol: {
          protocol: null,
          host: null,
          comPort: null,
        },
        param: '{}',
        active: true,
        state: '010',
      },
      instrumentTypes: [],
      instTypeMap: new Map(),
    };
  },
  computed: {
    hasDetailConfig() {
      if (this.form.instType === 'gps') return false;

      return true;
    },
    isTapiInstrument() {
      const tapi = ['t100', 't200', 't201', 't300', 't360', 't400', 't700'];
      for (const t of tapi) {
        if (this.form.instType === t) return true;
      }
      return false;
    },
    instrumentSummary() {
      const formNewline = input => {
        const newline = String.fromCharCode(13, 10);
        return input.replaceAll('\\n', newline);
      };

      let desc = `儀器ID:${this.form._id}\n`;
      desc += `儀器種類:${this.form.instType}\n`;
      desc += `通訊協定:${this.form.protocol.protocol}\n`;
      if (this.form.protocol.protocol === 'tcp')
        desc += `網址:${this.form.protocol.host}\n`;
      else desc += `COM:${this.form.protocol.comPort}\n`;

      if (this.isTapiInstrument) return (desc += this.tapiSummary());

      return formNewline(desc);
    },
  },
  mounted() {
    this.getInstrumentTypes();
  },
  methods: {
    tapiSummary() {
      let desc = '';
      const param = JSON.parse(this.form.param);
      desc += 'slave ID:' + param.slaveID + '\n';
      if (this.form.instType !== 't700') {
        desc += '校正時間:' + param.calibrationTime + '\n';
        desc += '校正上升時間:' + param.raiseTime + '\n';
        desc += '校正持續時間:' + param.holdTime + '\n';
        desc += '校正下降時間:' + param.downTime + '\n';
        if (param.calibrateZeoSeq)
          desc += '零點校正執行程序:' + param.calibrateZeoSeq + '\n';
        if (param.calibrateSpanSeq)
          desc += '全幅校正執行程序:' + param.calibrateSpanSeq + '\n';
        if (param.calibratorPurgeTime)
          desc += '校正器清空時間:' + param.calibratorPurgeTime + '\n';
        if (param.calibratorPurgeSeq)
          desc += '校正器清空執行程序:' + param.calibratorPurgeSeq + '\n';
        if (param.calibrateZeoDO)
          desc += '零點校正DO:' + param.calibrateZeoDO + '\n';
        if (param.calibrateSpanDO)
          desc += '全幅校正DO:' + param.calibrateSpanDO + '\n';
        if (param.skipInternalVault) desc += '不切換校正電磁閥::不切換';
      }
      return desc;
    },
    async getInstrumentTypes() {
      const res = await axios.get('/InstrumentTypes');
      this.instrumentTypes = res.data;
      for (const instType of res.data) {
        this.instTypeMap.set(instType.id, instType);
      }
    },
    getProtocolOptions() {
      if (this.form.instType) {
        const info = this.instTypeMap.get(this.form.instType).protocolInfo;
        return this.instTypeMap.get(this.form.instType).protocolInfo;
      } else return [];
    },
    validateInstType() {
      return new Promise((resolve, reject) => {
        this.$refs.instTypeRules.validate().then(success => {
          if (success) {
            resolve(true);
          } else {
            reject();
          }
        });
      });
    },
    validateProtocol() {
      return new Promise((resolve, reject) => {
        this.$refs.protocolRules.validate().then(success => {
          if (success) {
            resolve(true);
          } else {
            reject();
          }
        });
      });
    },
    validateDetailConfig() {
      return new Promise((resolve, reject) => {
        this.$refs.detailConfigRules.validate().then(success => {
          if (success) {
            resolve(true);
          } else {
            reject();
          }
        });
      });
    },
    onParamChange(v) {
      this.form.param = v;
      console.log(v);
    },
    async formSubmitted() {
      const res = await axios.post('/Instrument', this.form);
      const ret = res.data;
      if (ret.ok) {
        this.$emit('submit');
      } else {
        this.$toast({
          component: ToastificationContent,
          props: {
            title: '失敗',
            text: ret.msg,
            icon: 'EditIcon',
            variant: 'danger',
          },
        });
      }
    },
  },
};
</script>
