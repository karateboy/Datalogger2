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
      @on-change="onStepChange"
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
            <b-col
              v-if="
                form.protocol.protocol === 'tcp' ||
                form.protocol.protocol === 'tcpCli'
              "
              cols="12"
            >
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
              <b-form-group label="Speed" label-for="speed" label-cols-md="3">
                <validation-provider
                  v-slot="{ errors }"
                  name="速度"
                  rules="required"
                >
                  <b-form-select
                    v-model="form.protocol.speed"
                    :options="serialSpeed"
                  >
                  </b-form-select>
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
        :lazy="true"
        :before-change="validateDetailConfig"
      >
        <validation-observer ref="detailConfigRules">
          <tapi-config-page
            v-if="isCalibratable"
            :inst-type="form.instType"
            :param-str="form.param"
            @param-changed="onParamChange"
          />
          <adam-6017-config-page
            v-else-if="is8ChAnalogInput"
            :param-str="form.param"
            :has-addr="analogInputHasAddr"
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
          <sabio-config
            v-else-if="form.instType === 'sabio4010'"
            :param-str="form.param"
            @param-changed="onParamChange"
          />
          <duo-config2
            v-else-if="form.instType === 'duo'"
            :host="form.protocol.host"
            :param-str="form.param"
            :loading="loadingDetailedConfig"
            @param-changed="onParamChange"
          />
          <ak-config-page
            v-else-if="isAkInstrument"
            :param-str="form.param"
            @param-changed="onParamChange"
          ></ak-config-page>
          <adam-4000-config-page
            v-else-if="form.instType === 'adam4000'"
            :param-str="form.param"
            @param-changed="onParamChange"
          />
          <moxa-e-1212-config-page
            v-else-if="form.instType === 'moxaE1212'"
            :param-str="form.param"
            @param-changed="onParamChange"
          >
          </moxa-e-1212-config-page>
          <moxa-e-1240-config-page
            v-else-if="form.instType === 'moxaE1240'"
            :param-str="form.param"
            @param-changed="onParamChange"
          />
          <verewa-config
            v-else-if="form.instType === 'VEREWA_F701'"
            :param-str="form.param"
            @param-changed="onParamChange"
          />
          <met-one1020-config
            v-else-if="form.instType === 'MetOne1020'"
            :param-str="form.param"
            @param-changed="onParamChange"
          />
          <gps-config
            v-else-if="form.instType === 'gps'"
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
import axios from 'axios';
import Vue, { PropType } from 'vue';
import { ValidationObserver } from 'vee-validate';
import ToastificationContent from '@core/components/toastification/ToastificationContent.vue';
import TapiConfigPage from './TapiConfigPage.vue';
import Adam6017ConfigPage from './Adam6017ConfigPage.vue';
import MqttConfigPage from './MqttConfigPage.vue';
import Mqtt2ConfigPage from './Mqtt2ConfigPage.vue';
import Adam6066ConfigPage from './Adam6066ConfigPage.vue';
import ThetaConfigPage from './ThetaConfigPage.vue';
import SabioConfig from './SabioConfig.vue';
import DuoConfig2 from './DuoConfig2.vue';
import { TextStrValue } from './types';
import AkConfigPage from './AkConfigPage.vue';
import Adam4000ConfigPage from './Adam4000ConfigPage.vue';
import MoxaE1212ConfigPage from './Moxa1212ConfigPage.vue';
import MoxaE1240ConfigPage from './MoxaE1240ConfigPage.vue';
import VerewaConfig from './VerewaConfig.vue';
import MetOne1020Config from './MetOne1020Config.vue';
import GpsConfig from './GpsConfig.vue';

interface ProtocolParam {
  protocol: 'tcp' | 'serial' | 'tcpCli' | undefined;
  host?: string;
  comPort?: number;
  speed?: number;
}

interface ProtocolInfo {
  id: string;
  desp: string;
}

interface InstrumentTypeInfo {
  id: string;
  desp: string;
  protocolInfo: Array<ProtocolInfo>;
}

interface Instrument {
  _id: string;
  instType: string;
  protocol: ProtocolParam;
  param: string;
  active: boolean;
  state: string;
  statusType?: Array<any>;
}

export default Vue.extend({
  components: {
    ValidationObserver,
    TapiConfigPage,
    Adam6017ConfigPage,
    MqttConfigPage,
    Mqtt2ConfigPage,
    Adam6066ConfigPage,
    ThetaConfigPage,
    SabioConfig,
    DuoConfig2,
    AkConfigPage,
    Adam4000ConfigPage,
    MoxaE1212ConfigPage,
    MoxaE1240ConfigPage,
    VerewaConfig,
    MetOne1020Config,
    GpsConfig,
  },
  props: {
    isNew: {
      type: Boolean as PropType<boolean>,
      default: true,
    },
    inst: {
      type: Object as PropType<Instrument>,
      default: (): Instrument => {
        return {
          _id: '',
          instType: '',
          protocol: {
            protocol: 'tcp',
            host: undefined,
            comPort: undefined,
            speed: undefined,
          },
          param: '{}',
          active: true,
          state: '010',
        };
      },
    },
  },
  data() {
    const emptyForm: Instrument = {
      _id: '',
      instType: '',
      protocol: {
        protocol: undefined,
        host: undefined,
        comPort: undefined,
        speed: 9600,
      },
      param: '{}',
      active: true,
      state: '010',
    };

    let form: Instrument = this.isNew ? emptyForm : this.inst;
    let serialSpeed: Array<TextStrValue> = [
      {
        value: 2400,
        text: '2400',
      },
      {
        value: 4800,
        text: '4800',
      },
      {
        value: 9600,
        text: '9600',
      },
      {
        value: 19200,
        text: '19200',
      },
      {
        value: 38400,
        text: '38400',
      },
      {
        value: 38400,
        text: '38400',
      },
      {
        value: 115200,
        text: '115200',
      },
    ];
    return {
      form,
      instrumentTypes: [],
      instTypeMap: new Map<string, InstrumentTypeInfo>(),
      loadingDetailedConfig: false,
      serialSpeed,
    };
  },
  computed: {
    hasDetailConfig(): boolean {
      let list = ['tca08'];
      if (list.indexOf(this.form.instType) !== -1) return false;

      return true;
    },
    isCalibratable(): boolean {
      const types = [
        't100',
        't200',
        't201',
        't300',
        't360',
        't400',
        't700',
        'baseline9000',
        'horiba370',
        'picarroG2401',
        'EcoPhysics88P',
      ];
      for (const t of types) {
        if (this.form.instType === t) return true;
      }
      return this.form.instType.startsWith('TcpModbus.');
    },
    isAkInstrument(): boolean {
      return this.form.instType.startsWith('AkProtocol.');
    },
    is8ChAnalogInput(): boolean {
      const compatible = ['adam6017', 'MOXAE1240'];
      for (const t of compatible) {
        if (this.form.instType === t) return true;
      }
      return false;
    },
    analogInputHasAddr(): boolean {
      const compatible = ['MOXAE1240'];
      for (const t of compatible) {
        if (this.form.instType === t) return true;
      }
      return false;
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
      desc += `儀器種類:${this.getInstrumentDesc()}\n`;
      desc += `通訊協定:${this.form.protocol.protocol}\n`;
      if (this.form.protocol.protocol === 'tcp')
        desc += `網址:${this.form.protocol.host}\n`;
      else desc += `COM:${this.form.protocol.comPort}\n`;

      if (this.isCalibratable) return (desc += this.calibrationSummary());

      return formNewline(desc);
    },
  },
  async mounted() {
    this.getInstrumentTypes();
  },
  methods: {
    calibrationSummary() {
      let desc = '';
      const param = JSON.parse(this.form.param);
      desc += 'slave ID:' + param.slaveID + '\n';
      if (param.calibrationTime) desc += `校正時間: ${param.calibrationTime}\n`;
      if (param.raiseTime) desc += `校正上升時間: ${param.raiseTime}\n`;
      if (param.holdTime) desc += `校正持續時間: ${param.holdTime} \n`;
      if (param.downTime) desc += `校正下降時間: ${param.downTime} \n`;
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

      return desc;
    },
    async getInstrumentTypes(): Promise<void> {
      const res = await axios.get('/InstrumentTypes');
      this.instrumentTypes = res.data;
      let map = new Map<string, InstrumentTypeInfo>();
      for (const instType of res.data) {
        map.set(instType.id, instType as InstrumentTypeInfo);
      }
      this.instTypeMap = map;
    },
    getInstrumentDesc(): string {
      if (this.instTypeMap.get(this.form.instType)) {
        let info = this.instTypeMap.get(
          this.form.instType,
        ) as InstrumentTypeInfo;
        return info.desp;
      } else return '';
    },
    getProtocolOptions(): Array<ProtocolInfo> {
      if (this.form.instType && this.instTypeMap.get(this.form.instType)) {
        return this.instTypeMap.get(this.form.instType)!.protocolInfo;
      } else return [];
    },
    validateInstType(): Promise<any> {
      return new Promise((resolve, reject) => {
        if (this.$refs.instTypeRules !== null) {
          let rules = this.$refs.instTypeRules as any;
          rules = this.$refs.instTypeRules;
          rules.validate().then((success: boolean) => {
            if (success) {
              resolve(true);
            } else {
              reject();
            }
          });
        }
      });
    },
    validateProtocol(): Promise<any> {
      return new Promise((resolve, reject) => {
        if (this.$refs.protocolRules !== null) {
          let rules = this.$refs.protocolRules as any;
          rules.validate().then((success: boolean) => {
            if (success) {
              resolve(true);
            } else {
              reject();
            }
          });
        }
      });
    },
    validateDetailConfig(): Promise<any> {
      return new Promise((resolve, reject) => {
        if (this.$refs.detailConfigRules !== null) {
          let rules = this.$refs.detailConfigRules as any;
          rules.validate().then((success: boolean) => {
            if (success) {
              resolve(true);
            } else {
              reject();
            }
          });
        }
      });
    },
    onParamChange(v: string) {
      this.form.param = v;
    },
    onStepChange(prevIndex: number, nextIndex: number) {
      if (nextIndex === 2) this.loadingDetailedConfig = true;
    },
    async formSubmitted(): Promise<void> {
      this.form.statusType = undefined;
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
});
</script>
