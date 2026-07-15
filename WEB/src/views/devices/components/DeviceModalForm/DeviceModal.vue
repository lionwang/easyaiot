<template>
  <BasicModal
    @register="register"
    :title="getTitle"
    @cancel="handleCancel"
    :width="920"
    @ok="handleOk"
    :canFullscreen="false"
  >
    <div class="device-modal">
      <Spin :spinning="state.editLoading">
        <Form :labelCol="{ span: 3 }" :model="validateInfos" :wrapperCol="{ span: 21 }">
          <Row :gutter="12">
            <Col :span="12">
              <FormItem label="设备SN" name="deviceSn" v-bind="validateInfos.deviceSn"
                        :labelCol="{ span: 6 }" :wrapperCol="{ span: 18 }">
                <Input v-model:value="modelRef.deviceSn" />
              </FormItem>
            </Col>
            <Col :span="12">
              <FormItem label="应用场景" name="appId" v-bind="validateInfos.appId"
                        :labelCol="{ span: 6 }" :wrapperCol="{ span: 18 }">
                <Input v-model:value="modelRef.appId" />
              </FormItem>
            </Col>
            <Col :span="12">
              <FormItem label="设备名称" name="deviceName" v-bind="validateInfos.deviceName"
                        :labelCol="{ span: 6 }" :wrapperCol="{ span: 18 }">
                <Input v-model:value="modelRef.deviceName" />
              </FormItem>
            </Col>
            <Col :span="12">
              <FormItem label="客户端 ID" name="clientId"
                        :labelCol="{ span: 6 }" :wrapperCol="{ span: 18 }">
                <Input v-model:value="modelRef.clientId" placeholder="例如 tcp-client-01" />
              </FormItem>
            </Col>
            <Col :span="12">
              <FormItem label="所属产品" name="productIdentification" v-bind="validateInfos.productIdentification"
                        :labelCol="{ span: 6 }" :wrapperCol="{ span: 18 }">
                <Select
                  v-model:value="modelRef.productIdentification"
                  placeholder="所属产品"
                  :options="state.productList"
                  allowClear
                  @change="handleProductChange"
                />
              </FormItem>
            </Col>
          </Row>

          <Alert
            v-if="selectedProtocol"
            class="protocol-alert"
            type="info"
            show-icon
            :message="`接入协议：${selectedProtocolLabel}`"
          />

          <template v-if="selectedProtocol === 'MODBUS_TCP'">
            <Divider orientation="left">Modbus TCP 连接</Divider>
            <Row :gutter="12">
              <Col :span="10">
                <FormItem label="主机地址" :labelCol="{ span: 7 }" :wrapperCol="{ span: 17 }">
                  <Input v-model:value="protocolConfig.host" placeholder="192.168.1.100" />
                </FormItem>
              </Col>
              <Col :span="5">
                <FormItem label="端口" :labelCol="{ span: 8 }" :wrapperCol="{ span: 16 }">
                  <InputNumber v-model:value="protocolConfig.port" :min="1" :max="65535" />
                </FormItem>
              </Col>
              <Col :span="4">
                <FormItem label="站号" :labelCol="{ span: 10 }" :wrapperCol="{ span: 14 }">
                  <InputNumber v-model:value="protocolConfig.unitId" :min="0" :max="255" />
                </FormItem>
              </Col>
              <Col :span="5">
                <FormItem label="周期(ms)" :labelCol="{ span: 11 }" :wrapperCol="{ span: 13 }">
                  <InputNumber v-model:value="protocolConfig.pollIntervalMs" :min="1000" :step="1000" />
                </FormItem>
              </Col>
            </Row>
            <div class="point-header">
              <span>采集点位</span>
              <Button type="dashed" size="small" @click="addModbusPoint">
                <template #icon><PlusOutlined /></template>
                添加点位
              </Button>
            </div>
            <div v-for="(point, index) in protocolConfig.points" :key="index" class="point-row modbus-point-row">
              <Input v-model:value="point.identifier" placeholder="物模型标识" />
              <Select v-model:value="point.function" :options="modbusFunctionOptions" />
              <InputNumber v-model:value="point.address" :min="0" :max="65535" placeholder="地址" />
              <Select v-model:value="point.dataType" :options="modbusDataTypeOptions" />
              <InputNumber v-model:value="point.scale" :step="0.1" placeholder="倍率" />
              <Tooltip title="允许平台写入"><Switch v-model:checked="point.writable" size="small" /></Tooltip>
              <Tooltip title="删除点位">
                <Button danger type="text" @click="removePoint(index)"><DeleteOutlined /></Button>
              </Tooltip>
            </div>
          </template>

          <template v-else-if="selectedProtocol === 'OPCUA'">
            <Divider orientation="left">OPC UA 连接</Divider>
            <FormItem label="Endpoint">
              <Input v-model:value="protocolConfig.endpointUrl" placeholder="opc.tcp://192.168.1.100:4840" />
            </FormItem>
            <Row :gutter="12">
              <Col :span="8">
                <FormItem label="用户名" :labelCol="{ span: 8 }" :wrapperCol="{ span: 16 }">
                  <Input v-model:value="protocolConfig.username" placeholder="匿名可留空" />
                </FormItem>
              </Col>
              <Col :span="8">
                <FormItem label="密码" :labelCol="{ span: 7 }" :wrapperCol="{ span: 17 }">
                  <InputPassword v-model:value="protocolConfig.password" />
                </FormItem>
              </Col>
              <Col :span="8">
                <FormItem label="周期(ms)" :labelCol="{ span: 9 }" :wrapperCol="{ span: 15 }">
                  <InputNumber v-model:value="protocolConfig.pollIntervalMs" :min="1000" :step="1000" />
                </FormItem>
              </Col>
            </Row>
            <div class="point-header">
              <span>采集节点</span>
              <Button type="dashed" size="small" @click="addOpcUaPoint">
                <template #icon><PlusOutlined /></template>
                添加节点
              </Button>
            </div>
            <div v-for="(point, index) in protocolConfig.points" :key="index" class="point-row opcua-point-row">
              <Input v-model:value="point.identifier" placeholder="物模型标识" />
              <Input v-model:value="point.nodeId" placeholder="ns=2;s=Temperature" />
              <Select v-model:value="point.dataType" :options="opcUaDataTypeOptions" />
              <Tooltip title="允许平台写入"><Switch v-model:checked="point.writable" size="small" /></Tooltip>
              <Tooltip title="删除节点">
                <Button danger type="text" @click="removePoint(index)"><DeleteOutlined /></Button>
              </Tooltip>
            </div>
          </template>

          <Alert
            v-else-if="selectedProtocol === 'TCP'"
            class="protocol-alert"
            type="success"
            show-icon
            message="设备通过 TCP 长连接接入网关 8091 端口，首包使用 auth 方法认证。"
          />

          <FormItem label="设备描述" name="deviceDescription" v-bind="validateInfos.deviceDescription">
            <Input v-model:value="modelRef.deviceDescription" />
          </FormItem>
          <FormItem label="备注" name="remark" v-bind="validateInfos.remark">
            <Textarea v-model:value="modelRef.remark" placeholder="请输入备注" :maxlength="200" :rows="3" showCount />
          </FormItem>
        </Form>
      </Spin>
    </div>
  </BasicModal>
</template>

<script lang="ts" setup>
import {computed, onMounted, reactive} from 'vue';
import {BasicModal, useModalInner} from '@/components/Modal';
import {
  Alert, Button, Col, Divider, Form, FormItem, Input, InputNumber, Row, Select, Spin, Switch, Textarea, Tooltip,
} from 'ant-design-vue';
import {DeleteOutlined, PlusOutlined} from '@ant-design/icons-vue';
import {useMessage} from '@/hooks/web/useMessage';
import {getDeviceProfiles} from '@/api/device/product';
import {saveDevices, updateDevices} from '@/api/device/devices';

const {createMessage} = useMessage();
const InputPassword = Input.Password;

const state = reactive<any>({
  productList: [],
  record: null,
  productType: true,
  editLoading: false,
});

const modelRef = reactive<any>({
  id: '',
  clientId: '',
  deviceSn: '',
  appId: '',
  deviceName: '',
  productIdentification: '',
  deviceDescription: '',
  ipAddress: '',
  extension: '',
  remark: '',
});

const protocolConfig = reactive<any>(createProtocolConfig(''));
const selectedProduct = computed(() => state.productList.find(
  (item: any) => item.value === modelRef.productIdentification,
));
const selectedProtocol = computed(() => selectedProduct.value?.protocolType || '');
const protocolLabels: Record<string, string> = {
  MQTT: 'MQTT', HTTP: 'HTTP', TCP: 'TCP', MODBUS_TCP: 'Modbus TCP', OPCUA: 'OPC UA',
};
const selectedProtocolLabel = computed(() => protocolLabels[selectedProtocol.value] || selectedProtocol.value);
const getTitle = computed(() => (state.productType ? '新增设备' : '编辑设备'));

const modbusFunctionOptions = [
  {label: '保持寄存器', value: 'HOLDING_REGISTER'},
  {label: '输入寄存器', value: 'INPUT_REGISTER'},
  {label: '线圈', value: 'COIL'},
  {label: '离散输入', value: 'DISCRETE_INPUT'},
];
const modbusDataTypeOptions = ['INT16', 'UINT16', 'INT32', 'UINT32', 'FLOAT32', 'INT64', 'FLOAT64']
  .map((value) => ({label: value, value}));
const opcUaDataTypeOptions = ['AUTO', 'BOOLEAN', 'INT32', 'INT64', 'FLOAT', 'DOUBLE', 'STRING']
  .map((value) => ({label: value, value}));

onMounted(initProductList);

async function initProductList() {
  const record = await getDeviceProfiles({page: 1, pageSize: 100});
  const list = record?.data || [];
  state.productList = list.map((item: any) => ({
    ...item,
    value: item.productIdentification,
    label: `${item.productName} · ${item.protocolType || '未配置协议'}`,
  }));
  if (state.record) loadProtocolConfig(state.record);
}

function createProtocolConfig(type: string) {
  return {
    type,
    enabled: true,
    host: '',
    port: type === 'MODBUS_TCP' ? 502 : 4840,
    unitId: 1,
    endpointUrl: '',
    username: '',
    password: '',
    pollIntervalMs: 5000,
    points: [],
  };
}

function replaceProtocolConfig(config: Record<string, any>) {
  Object.keys(protocolConfig).forEach((key) => delete protocolConfig[key]);
  Object.assign(protocolConfig, config);
}

function handleProductChange() {
  replaceProtocolConfig(createProtocolConfig(selectedProtocol.value));
}

function addModbusPoint() {
  protocolConfig.points.push({
    identifier: '', function: 'HOLDING_REGISTER', address: 0, quantity: 1,
    dataType: 'UINT16', byteOrder: 'BIG_ENDIAN', wordOrder: 'BIG_ENDIAN', scale: 1, offset: 0, writable: false,
  });
}

function addOpcUaPoint() {
  protocolConfig.points.push({identifier: '', nodeId: '', dataType: 'AUTO', writable: false});
}

function removePoint(index: number) {
  protocolConfig.points.splice(index, 1);
}

const [register, {closeModal}] = useModalInner((data) => {
  state.productType = data.type;
  if (data.type) {
    resetModel();
  } else {
    productEdit(data.record);
  }
});

const emits = defineEmits(['success']);
const rulesRef = reactive({
  deviceSn: [{required: true, message: '请输入设备SN', trigger: ['change']}],
  appId: [{required: true, message: '请输入应用场景', trigger: ['change']}],
  deviceName: [{required: true, message: '请输入设备名称', trigger: ['change']}],
  productIdentification: [{required: true, message: '请选择所属产品', trigger: ['change']}],
});
const {validate, resetFields, validateInfos} = Form.useForm(modelRef, rulesRef);

function resetModel() {
  Object.assign(modelRef, {
    id: '', clientId: '', deviceSn: '', appId: '', deviceName: '', productIdentification: '',
    deviceDescription: '', ipAddress: '', extension: '', remark: '',
  });
  replaceProtocolConfig(createProtocolConfig(''));
  state.record = null;
  resetFields();
}

function loadProtocolConfig(record: any) {
  let extension: any = {};
  try {
    extension = record?.extension ? JSON.parse(record.extension) : {};
  } catch (_) {
    extension = {};
  }
  replaceProtocolConfig({
    ...createProtocolConfig(selectedProtocol.value),
    ...(extension.protocolConfig || {}),
    points: extension.protocolConfig?.points || [],
  });
}

function productEdit(record: any) {
  state.editLoading = true;
  try {
    Object.keys(modelRef).forEach((item) => {
      modelRef[item] = record?.[item] ?? '';
    });
    state.record = record;
    loadProtocolConfig(record);
  } finally {
    state.editLoading = false;
  }
}

function handleCancel() {
  resetModel();
}

function validateProtocolConfig() {
  if (selectedProtocol.value === 'MODBUS_TCP' && !protocolConfig.host) {
    createMessage.error('请输入 Modbus TCP 主机地址');
    return false;
  }
  if (selectedProtocol.value === 'OPCUA' && !protocolConfig.endpointUrl) {
    createMessage.error('请输入 OPC UA Endpoint');
    return false;
  }
  if (['MODBUS_TCP', 'OPCUA'].includes(selectedProtocol.value)) {
    if (!protocolConfig.points.length || protocolConfig.points.some((point: any) => !point.identifier)) {
      createMessage.error('请至少配置一个完整的采集点位');
      return false;
    }
    if (selectedProtocol.value === 'OPCUA' && protocolConfig.points.some((point: any) => !point.nodeId)) {
      createMessage.error('OPC UA 节点 ID 不能为空');
      return false;
    }
  }
  return true;
}

function handleOk() {
  validate().then(() => {
    if (!validateProtocolConfig()) return;
    const payload: any = {...modelRef};
    let extension: any = {};
    try {
      extension = modelRef.extension ? JSON.parse(modelRef.extension) : {};
    } catch (_) {
      extension = {};
    }
    if (['MODBUS_TCP', 'OPCUA'].includes(selectedProtocol.value)) {
      extension.protocolConfig = {...protocolConfig, type: selectedProtocol.value};
      payload.extension = JSON.stringify(extension);
      if (selectedProtocol.value === 'MODBUS_TCP') payload.ipAddress = protocolConfig.host;
    } else {
      delete extension.protocolConfig;
      payload.extension = Object.keys(extension).length ? JSON.stringify(extension) : '';
    }

    state.editLoading = true;
    const api = modelRef.id ? updateDevices : saveDevices;
    api(payload)
      .then(() => {
        createMessage.success('操作成功');
        closeModal();
        resetModel();
        emits('success');
      })
      .finally(() => {
        state.editLoading = false;
      });
  }).catch((err) => {
    if (!err?.errorFields) createMessage.error('操作失败');
  });
}
</script>

<style lang="less" scoped>
.device-modal {
  :deep(.ant-form-item-label > label::after) {
    content: '';
  }

  :deep(.ant-input-number) {
    width: 100%;
  }
}

.protocol-alert {
  margin-bottom: 16px;
}

.point-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 8px 0;
  font-weight: 600;
}

.point-row {
  display: grid;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.modbus-point-row {
  grid-template-columns: 1.2fr 1.25fr 84px 100px 76px 40px 32px;
}

.opcua-point-row {
  grid-template-columns: 1fr 1.8fr 100px 40px 32px;
}
</style>
