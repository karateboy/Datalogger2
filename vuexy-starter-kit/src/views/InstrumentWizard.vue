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
            <b-col cols="12">
              <b-form-group label="群組" label-for="inst-id" label-cols-md="3">
                <v-select
                  id="instrumentGroup"
                  v-model="form.group"
                  label="name"
                  :reduce="group => group._id"
                  :options="groupList"
                />
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
          <adam-6017-config-page
            v-else-if="isAdam6017"
            :param-str="form.param"
            @param-changed="onParamChange"
          />
          <mqtt-config-page
            v-else-if="isMqtt"
            :param-str="form.param"
            @param-changed="onParamChange"
          />
          <mqtt2-config-page
            v-else-if="isMqtt2"
            :param-str="form.param"
            @param-changed="onParamChange"
          />
          <adam-6066-config-page
            v-else-if="isAdam6066"
            :param-str="form.param"
            @param-changed="onParamChange"
          ></adam-6066-config-page>
          <theta-config-page
            v-else-if="isTheta"
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

<script lang="ts">
import Vue, { PropType } from 'vue';
import axios from 'axios';
import { Instrument, InstrumentTypeInfo, ProtocolInfo, Group } from './types';

import { ValidationObserver } from 'vee-validate';
import ToastificationContent from '@core/components/toastification/ToastificationContent.vue';
import TapiConfigPage from './TapiConfigPage.vue';
import Adam6017ConfigPage from './Adam6017ConfigPage.vue';
import MqttConfigPage from './MqttConfigPage.vue';
import Mqtt2ConfigPage from './Mqtt2ConfigPage.vue';
import Adam6066ConfigPage from './Adam6066ConfigPage.vue';
import ThetaConfigPage from './ThetaConfigPage.vue';

const emptyForm: Instrument = {
  _id: '',
  instType: '',
  protocol: {
    protocol: '',
    host: undefined,
    comPort: undefined,
  },
  param: '',
  active: true,
  state: '010',
};

export default Vue.extend({
  components: {
    ValidationObserver,
    TapiConfigPage,
    Adam6017ConfigPage,
    MqttConfigPage,
    Mqtt2ConfigPage,
    Adam6066ConfigPage,
    ThetaConfigPage,
  },
  props: {
    isNew: {
      type: Boolean as PropType<boolean>,
      default: true,
    },
    inst: {
      type: Object as PropType<Instrument>,
      default: () => emptyForm,
    },
  },
  data() {
    let form: Instrument = emptyForm;
    let isNew = this.isNew;
    if (!isNew) {
      form = Object.assign({}, this.inst);
    }

    const groupList = Array<Group>();
    return {
      form,
      instrumentTypes: Array<string>(),
      instTypeMap: new Map<string, InstrumentTypeInfo>(),
      groupList,
    };
  },
  computed: {
    hasDetailConfig(): boolean {
      if (this.form.instType === 'gps') return false;

      return true;
    },
    isTapiInstrument(): boolean {
      const tapi = ['t100', 't200', 't201', 't300', 't360', 't400', 't700'];
      for (const t of tapi) {
        if (this.form.instType === t) return true;
      }
      return false;
    },
    isAdam6017(): boolean {
      return this.form.instType === 'adam6017';
    },
    isMqtt(): boolean {
      return this.form.instType === 'mqtt_client';
    },
    isMqtt2(): boolean {
      return this.form.instType === 'mqtt_client2';
    },
    isAdam6066(): boolean {
      return this.form.instType === 'adam6066';
    },
    isTheta(): boolean {
      return this.form.instType === 'theta';
    },
    instrumentSummary(): string {
      const formNewline = (input: string) => {
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
  async mounted() {
    this.getInstrumentTypes();
    await this.getGroupList();
  },
  methods: {
    tapiSummary(): string {
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
    async getInstrumentTypes(): Promise<void> {
      const res = await axios.get('/InstrumentTypes');
      this.instrumentTypes = res.data;
      for (const instType of res.data) {
        this.instTypeMap.set(instType.id, instType);
      }
    },
    getProtocolOptions(): Array<ProtocolInfo> {
      if (this.form.instType && this.instTypeMap.get(this.form.instType)) {
        let instrumentTypeInfo = this.instTypeMap.get(
          this.form.instType,
        ) as InstrumentTypeInfo;
        return instrumentTypeInfo.protocolInfo;
      } else return [];
    },
    validateInstType(): Promise<boolean> {
      return new Promise((resolve, reject) => {
        let validateOserver = this.$refs['instTypeRules'] as InstanceType<
          typeof ValidationObserver
        >;
        validateOserver.validate().then(success => {
          if (success) {
            resolve(true);
          } else {
            reject();
          }
        });
      });
    },
    validateProtocol(): Promise<boolean> {
      return new Promise((resolve, reject) => {
        let validateOserver = this.$refs['protocolRules'] as InstanceType<
          typeof ValidationObserver
        >;
        validateOserver.validate().then((success: boolean) => {
          if (success) {
            resolve(true);
          } else {
            reject();
          }
        });
      });
    },
    validateDetailConfig(): Promise<boolean> {
      return new Promise((resolve, reject) => {
        let validateOserver = this.$refs['detailConfigRules'] as InstanceType<
          typeof ValidationObserver
        >;
        validateOserver.validate().then((success: boolean) => {
          if (success) {
            resolve(true);
          } else {
            reject();
          }
        });
      });
    },
    onParamChange(v: string): void {
      this.form.param = v;
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
    async getGroupList() {
      const res = await axios.get('/Groups');
      if (res.status === 200) {
        this.groupList = res.data;
      }
    },
  },
});
</script>
