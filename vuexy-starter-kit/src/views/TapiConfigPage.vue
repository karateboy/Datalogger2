<template>
  <b-form @change="onChange">
    <b-row>
      <b-col cols="12">
        <validation-provider
          v-slot="{ errors }"
          name="Slave ID"
          rules="required"
        >
          <b-form-group label="Slave ID" label-for="slave-id" label-cols-md="3">
            <b-form-input
              v-model.number="paramObj.slaveID"
              type="number"
            ></b-form-input>
            <small class="text-danger">{{ errors[0] }}</small>
          </b-form-group>
        </validation-provider>
      </b-col>
    </b-row>
    <b-row v-if="instType !== 't700'">
      <b-col cols="12">
        <b-form-group
          label="每日校正時間"
          label-for="slave-id"
          label-cols-md="3"
        >
          <b-form-input
            v-model="paramObj.calibrationTime"
            type="time"
          ></b-form-input>
        </b-form-group>
      </b-col>
      <b-col cols="12">
        <b-form-group
          label="校正上升時間(秒)"
          label-for="raiseTime"
          label-cols-md="3"
        >
          <b-form-input
            v-model.number="paramObj.raiseTime"
            type="number"
          ></b-form-input>
        </b-form-group>
      </b-col>
      <b-col cols="12">
        <b-form-group
          label="全幅持續時間(秒)"
          label-for="holdTime"
          label-cols-md="3"
        >
          <b-form-input
            v-model.number="paramObj.holdTime"
            type="number"
          ></b-form-input>
        </b-form-group>
      </b-col>
      <b-col cols="12">
        <b-form-group
          label="校正下降時間(秒)"
          label-for="downTime"
          label-cols-md="3"
        >
          <b-form-input
            v-model.number="paramObj.downTime"
            type="number"
          ></b-form-input>
        </b-form-group>
      </b-col>
      <b-col cols="12">
        <b-form-group
          label="零點校正執行Sequence:"
          label-for="downTime"
          label-cols-md="3"
        >
          <b-form-input
            v-model.number="paramObj.calibrateZeoSeq"
            type="number"
          ></b-form-input>
        </b-form-group>
      </b-col>
      <b-col cols="12">
        <b-form-group
          label="全幅校正執行Sequence:"
          label-for="downTime"
          label-cols-md="3"
        >
          <b-form-input
            v-model.number="paramObj.calibrateSpanSeq"
            type="number"
          ></b-form-input>
        </b-form-group>
      </b-col>
      <b-col cols="12">
        <b-form-group
          label="校正器清空時間(秒):"
          label-for="downTime"
          label-cols-md="3"
        >
          <b-form-input
            v-model.number="paramObj.calibratorPurgeTime"
            type="number"
          ></b-form-input>
        </b-form-group>
      </b-col>
      <b-col cols="12">
        <b-form-group
          label="校正器清空執行Sequence:"
          label-for="downTime"
          label-cols-md="3"
        >
          <b-form-input
            v-model.number="paramObj.calibratorPurgeSeq"
            type="number"
          ></b-form-input>
        </b-form-group>
      </b-col>
      <b-col cols="12">
        <b-form-group
          label="零點校正執行DO:"
          label-for="calibrateZeoDO"
          label-cols-md="3"
        >
          <b-form-input
            v-model.number="paramObj.calibrateZeoDO"
            type="number"
          ></b-form-input>
        </b-form-group>
      </b-col>
      <b-col cols="12">
        <b-form-group
          label="全幅校正執行DO:"
          label-for="calibrateZeoDO"
          label-cols-md="3"
        >
          <b-form-input
            v-model.number="paramObj.calibrateSpanDO"
            type="number"
          ></b-form-input>
        </b-form-group>
      </b-col>
      <b-col cols="12">
        <b-form-group
          label="不切換校正電磁閥:"
          label-for="calibrateZeoDO"
          label-cols-md="3"
        >
          <b-form-checkbox
            v-model="paramObj.skipInternalVault"
          ></b-form-checkbox>
        </b-form-group>
      </b-col>
    </b-row>
  </b-form>
</template>
<script>
import Vue from 'vue';
export default Vue.extend({
  props: {
    instType: {
      type: String,
      default: 't100',
    },
    paramStr: {
      type: String,
      default: ``,
    },
  },
  data() {
    let paramObj = {
      slaveID: 1,
      calibrationTime: null,
      monitorTypes: null,
      raiseTime: null,
      downTime: null,
      holdTime: null,
      calibrateZeoSeq: null,
      calibrateSpanSeq: null,
      calibratorPurgeSeq: null,
      calibratorPurgeTime: null,
      calibrateZeoDO: null,
      calibrateSpanDO: null,
      skipInternalVault: null,
    };
    if (this.paramStr !== '') paramObj = JSON.parse(this.paramStr);

    return {
      paramObj,
    };
  },
  methods: {
    justify() {
      const paramObj = this.paramObj;
      if (paramObj.calibrationTime === '') paramObj.calibrationTime = null;
      if (paramObj.raiseTime === '') paramObj.raiseTime = null;
      if (paramObj.holdTime === '') paramObj.holdTime = null;
      if (paramObj.downTime === '') paramObj.downTime = null;
      if (paramObj.calibrateZeoSeq === '') paramObj.calibrateZeoSeq = null;
      if (paramObj.calibrateSpanSeq === '') paramObj.calibrateSpanSeq = null;
      if (paramObj.calibratePurgeSeq === '') paramObj.calibratePurgeSeq = null;
      if (paramObj.calibratePurgeTime === '')
        paramObj.calibratePurgeTime = null;
      if (paramObj.calibrateZeoDO === '') paramObj.calibrateZeoDO = null;
      if (paramObj.calibrateSpanDO === '') paramObj.calibrateSpanDO = null;
    },
    onChange(evt) {
      this.justify();
      this.$emit('param-changed', JSON.stringify(this.paramObj));
    },
  },
});
</script>
