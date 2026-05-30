<template>
  <Spin :spinning="state.scanning || state.registering">
    <DeviceCreatePanelLayout result-title="扫描结果">
      <template #form>
        <BasicForm @register="registerForm">
          <template #credentials>
            <div class="credentials-block">
              <div v-for="(cred, idx) in credentials" :key="idx" class="credential-row">
                <Input
                  v-model:value="cred.username"
                  placeholder="用户名"
                  :disabled="state.scanning"
                  class="cred-user"
                  allow-clear
                />
                <Input.Password
                  v-model:value="cred.password"
                  placeholder="密码"
                  :disabled="state.scanning"
                  class="cred-pass"
                  allow-clear
                />
                <Button
                  type="link"
                  danger
                  size="small"
                  :disabled="state.scanning || credentials.length <= 1"
                  @click="removeCredential(idx)"
                >
                  删除
                </Button>
              </div>
              <Button type="dashed" block :disabled="state.scanning" @click="addCredential">添加凭证</Button>
            </div>
          </template>
        </BasicForm>
      </template>
      <template #actions>
        <Button type="primary" :loading="state.scanning" pre-icon="ant-design:search-outlined" @click="handleScan">
          开始跨网段扫描
        </Button>
        <span v-if="state.scanProgress" class="dc-action-tip">{{ state.scanProgress }}</span>
      </template>
      <template v-if="state.devices.length" #resultExtra>
        <Button
          type="primary"
          :loading="state.batchRegistering"
          :disabled="registrableCount === 0"
          @click="handleBatchRegister"
        >
          {{ batchRegisterButtonText }}
        </Button>
        <span v-if="state.batchProgress" class="dc-action-tip">{{ state.batchProgress }}</span>
      </template>
      <template #result>
        <Table
          v-if="state.devices.length"
          :columns="mode === 'camera' ? cameraColumns : nvrColumns"
          :data-source="state.devices"
          :pagination="tablePagination"
          :scroll="{ x: 1000 }"
          row-key="ip"
          size="small"
          bordered
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'register_status'">
              <Tag :color="registerStatusColor(record.ip)">{{ registerStatusLabel(record.ip, record) }}</Tag>
            </template>
            <template v-else-if="column.dataIndex === 'action'">
              <Button
                type="link"
                size="small"
                :disabled="!canRegisterRecord(record)"
                :loading="state.registeringIp === record.ip"
                @click="mode === 'nvr' ? handleRegisterNvr(record) : handleRegisterCamera(record)"
              >
                {{ mode === 'nvr' ? '登记' : '注册' }}
              </Button>
            </template>
          </template>
        </Table>
        <Empty v-else description="填写扫描参数并点击「开始跨网段扫描」" />
      </template>
    </DeviceCreatePanelLayout>
  </Spin>
</template>

<script lang="ts" setup>
import { computed, reactive, watch } from 'vue';
import { Empty, Input, Spin, Table, Tag } from 'ant-design-vue';
import { BasicForm, useForm } from '@/components/Form';
import { Button } from '@/components/Button';
import { useMessage } from '@/hooks/web/useMessage';
import DeviceCreatePanelLayout from '../DeviceCreatePanelLayout.vue';
import { SEGMENT_SCAN_TARGETS_PLACEHOLDER } from '../segmentScanGuide';
import {
  DEVICE_CREATE_COL_FULL,
  DEVICE_CREATE_FORM_GRID,
  DEVICE_CREATE_NUMBER_PROPS,
} from '../deviceCreateForm';
import {
  registerDevice,
  registerNvrWithChannels,
  scanSegmentDevices,
  type CredentialPair,
  type NvrInfo,
  type SegmentScanDeviceRow,
} from '@/api/device/camera';
import { getCameraScanColumns, getNvrScanColumns } from '@/views/camera/components/SegmentScanModal/Data';

const props = defineProps<{ mode?: 'camera' | 'nvr' }>();
const emit = defineEmits<{ success: [] }>();

const { createMessage } = useMessage();

type RegisterStatus = 'idle' | 'success' | 'failed' | 'skipped';

const state = reactive({
  mode: 'camera' as 'camera' | 'nvr',
  scanning: false,
  registering: false,
  batchRegistering: false,
  registeringIp: '' as string,
  devices: [] as SegmentScanDeviceRow[],
  scanProgress: '',
  batchProgress: '',
  registerStatusMap: {} as Record<string, RegisterStatus>,
});

const credentials = reactive<CredentialPair[]>([{ username: 'admin', password: '' }]);

const [registerForm, { validate, getFieldsValue, updateSchema }] = useForm({
  ...DEVICE_CREATE_FORM_GRID,
  schemas: [
    {
      field: 'targets',
      label: '目标网段 / IP',
      component: 'InputTextArea',
      required: true,
      colProps: { ...DEVICE_CREATE_COL_FULL, class: 'segment-scan-targets' },
      componentProps: {
        autoSize: { minRows: 3, maxRows: 12 },
        placeholder: SEGMENT_SCAN_TARGETS_PLACEHOLDER,
      },
    },
    {
      field: 'ports',
      label: 'Web 端口',
      component: 'Input',
      defaultValue: '80,443,8000,8443',
    },
    {
      field: '_credentials',
      label: '登录凭证',
      component: 'Input',
      colProps: { ...DEVICE_CREATE_COL_FULL, class: 'segment-scan-credentials' },
      slot: 'credentials',
      itemProps: { autoLink: false },
      rules: [],
    },
    {
      field: 'concurrency',
      label: '并发数',
      component: 'InputNumber',
      defaultValue: 200,
      componentProps: { min: 1, max: 2000, ...DEVICE_CREATE_NUMBER_PROPS },
    },
    {
      field: 'timeout',
      label: '超时(秒)',
      component: 'InputNumber',
      defaultValue: 5,
      componentProps: { min: 0.5, max: 60, step: 0.5, ...DEVICE_CREATE_NUMBER_PROPS },
    },
    {
      field: 'only_hits',
      label: '结果过滤',
      component: 'Switch',
      defaultValue: true,
      componentProps: {
        checkedChildren: '已识别',
        unCheckedChildren: '全部',
      },
    },
  ],
});

watch(
  () => props.mode,
  (m) => {
    state.mode = m || 'camera';
    state.devices = [];
    resetCredentials();
    resetRegisterStatus();
  },
  { immediate: true },
);

watch(
  () => state.scanning,
  (scanning) => {
    updateSchema([
      { field: 'targets', componentProps: { disabled: scanning, autoSize: { minRows: 3, maxRows: 12 } } },
      { field: 'ports', componentProps: { disabled: scanning } },
      { field: 'concurrency', componentProps: { disabled: scanning, min: 1, max: 2000, ...DEVICE_CREATE_NUMBER_PROPS } },
      { field: 'timeout', componentProps: { disabled: scanning, min: 0.5, max: 60, step: 0.5, ...DEVICE_CREATE_NUMBER_PROPS } },
      { field: 'only_hits', componentProps: { disabled: scanning } },
    ]);
  },
);

const mode = computed(() => state.mode);
const cameraColumns = getCameraScanColumns();
const nvrColumns = getNvrScanColumns();
const tablePagination = {
  pageSize: 10,
  size: 'small' as const,
  showSizeChanger: false,
  showTotal: (total: number) => `共 ${total} 条`,
};

function getValidCredentials(): CredentialPair[] {
  return credentials
    .map((c) => ({ username: (c.username || '').trim(), password: c.password || '' }))
    .filter((c) => c.username);
}

function resetCredentials() {
  credentials.splice(0, credentials.length, { username: 'admin', password: '' });
}

function addCredential() {
  credentials.push({ username: '', password: '' });
}

function removeCredential(index: number) {
  if (credentials.length <= 1) return;
  credentials.splice(index, 1);
}

function resolveCredential(authUsername: string | undefined, credentials: CredentialPair[]): CredentialPair {
  if (authUsername) {
    const found = credentials.find((c) => c.username === authUsername);
    if (found) return found;
  }
  return credentials[0];
}

function isCredentialAccessible(record: SegmentScanDeviceRow): boolean {
  return !!(record.auth_username && String(record.auth_username).trim());
}

function hasRegisterPayload(record: SegmentScanDeviceRow): boolean {
  if (state.mode === 'nvr') return isCredentialAccessible(record);
  return !!record.rtsp_url;
}

function isAlreadyRegistered(ip: string): boolean {
  return state.registerStatusMap[ip] === 'success';
}

function canRegisterRecord(record: SegmentScanDeviceRow): boolean {
  if (isAlreadyRegistered(record.ip)) return false;
  if (state.batchRegistering || state.registering) return false;
  return isCredentialAccessible(record) && hasRegisterPayload(record);
}

function getRegistrableDevices(): SegmentScanDeviceRow[] {
  return state.devices.filter((d) => canRegisterRecord(d));
}

const registrableCount = computed(() => getRegistrableDevices().length);

const batchRegisterButtonText = computed(() => {
  const n = registrableCount.value;
  if (state.mode === 'nvr') {
    return n > 0 ? `批量登记（${n}）` : '批量登记';
  }
  return n > 0 ? `批量注册（${n}）` : '批量注册';
});

function registerStatusLabel(ip: string, record: SegmentScanDeviceRow): string {
  const st = state.registerStatusMap[ip];
  if (st === 'success') return '已注册';
  if (st === 'failed') return '注册失败';
  if (!isCredentialAccessible(record)) return '未认证';
  if (!hasRegisterPayload(record)) return state.mode === 'nvr' ? '不可登记' : '无 RTSP';
  return '可注册';
}

function registerStatusColor(ip: string): string {
  const st = state.registerStatusMap[ip];
  if (st === 'success') return 'success';
  if (st === 'failed') return 'error';
  return 'processing';
}

function resetRegisterStatus() {
  state.registerStatusMap = {};
  state.batchProgress = '';
  state.registeringIp = '';
}

function vendorToCameraType(vendor?: string): string {
  if (vendor === 'hikvision') return 'hikvision';
  if (vendor === 'dahua') return 'dahua';
  return 'custom';
}

async function handleScan() {
  try {
    await validate();
  } catch {
    return;
  }
  const values = getFieldsValue();
  const creds = getValidCredentials();
  if (!creds.length) {
    createMessage.warning('请至少填写一组登录凭证');
    return;
  }
  state.scanning = true;
  state.scanProgress = '正在跨网段扫描，请稍候…';
  state.devices = [];
  resetRegisterStatus();
  try {
    const res = await scanSegmentDevices({
      targets: String(values.targets || '').trim(),
      credentials: creds,
      ports: String(values.ports || '').trim() || undefined,
      concurrency: values.concurrency,
      timeout: values.timeout,
      only_hits: !!values.only_hits,
      nvr_only: state.mode === 'nvr',
      exclude_nvr: state.mode === 'camera',
    });
    const list = (res as { data?: SegmentScanDeviceRow[] })?.data ?? (res as SegmentScanDeviceRow[]) ?? [];
    state.devices = Array.isArray(list) ? list : [];
    if (!state.devices.length) {
      createMessage.info(state.mode === 'nvr' ? '未发现 NVR 设备' : '未发现可识别设备');
    } else {
      createMessage.success(`扫描完成，共 ${state.devices.length} 台`);
    }
  } catch (e: unknown) {
    const err = e as { msg?: string; message?: string };
    createMessage.error(err?.msg || err?.message || '扫描失败');
  } finally {
    state.scanning = false;
    state.scanProgress = '';
  }
}

async function registerOneNvr(record: SegmentScanDeviceRow, credentials: CredentialPair[], timeout: number, silent = false): Promise<boolean> {
  if (!canRegisterRecord(record)) return false;
  const cred = resolveCredential(record.auth_username, credentials);
  state.registeringIp = record.ip;
  try {
    const res = await registerNvrWithChannels({
      ip: record.ip,
      port: record.port || 80,
      username: cred.username,
      password: cred.password,
      credentials,
      timeout,
      vendor: record.vendor,
      name: record.device_name,
      model: record.model,
      serial_number: record.serial,
      rtsp_url: record.rtsp_url,
      scheme: record.port && [443, 8443].includes(record.port) ? 'https' : 'http',
    });
    const stats = (res as { stats?: { registered?: number } })?.stats;
    const n = stats?.registered ?? (res as NvrInfo)?.camera_count ?? 0;
    state.registerStatusMap[record.ip] = 'success';
    if (!silent) createMessage.success(`NVR ${record.ip} 已登记，已挂载 ${n} 路通道`);
    return true;
  } catch (e: unknown) {
    state.registerStatusMap[record.ip] = 'failed';
    if (!silent) {
      const err = e as { msg?: string; message?: string };
      createMessage.error(err?.msg || err?.message || `NVR ${record.ip} 登记失败`);
    }
    return false;
  } finally {
    if (state.registeringIp === record.ip) state.registeringIp = '';
  }
}

async function registerOneCamera(record: SegmentScanDeviceRow, credentials: CredentialPair[], timeout: number, silent = false): Promise<boolean> {
  if (!canRegisterRecord(record)) {
    if (!silent) createMessage.warning('该设备无法注册');
    return false;
  }
  const cred = resolveCredential(record.auth_username, credentials);
  state.registeringIp = record.ip;
  try {
    await registerDevice({
      name: record.device_name || `${record.vendor_label || '设备'}-${record.ip}`,
      source: record.rtsp_url!,
      ip: record.ip,
      port: 554,
      username: cred.username,
      password: cred.password,
      cameraType: vendorToCameraType(record.vendor),
      skip_onvif: true,
      stream: 0,
      manufacturer: record.vendor_label,
      model: record.model,
      serial_number: record.serial,
    });
    state.registerStatusMap[record.ip] = 'success';
    if (!silent) createMessage.success(`摄像头 ${record.ip} 注册成功`);
    return true;
  } catch (e: unknown) {
    state.registerStatusMap[record.ip] = 'failed';
    if (!silent) {
      const err = e as { msg?: string; message?: string };
      createMessage.error(err?.msg || err?.message || `摄像头 ${record.ip} 注册失败`);
    }
    return false;
  } finally {
    if (state.registeringIp === record.ip) state.registeringIp = '';
  }
}

async function handleRegisterNvr(record: SegmentScanDeviceRow) {
  const values = getFieldsValue();
  const creds = getValidCredentials();
  state.registering = true;
  try {
    if (await registerOneNvr(record, creds, Number(values.timeout) || 5)) emit('success');
  } finally {
    state.registering = false;
  }
}

async function handleRegisterCamera(record: SegmentScanDeviceRow) {
  const values = getFieldsValue();
  const creds = getValidCredentials();
  state.registering = true;
  try {
    if (await registerOneCamera(record, creds, Number(values.timeout) || 5)) emit('success');
  } finally {
    state.registering = false;
  }
}

async function handleBatchRegister() {
  const list = getRegistrableDevices();
  if (!list.length) {
    createMessage.warning('没有可注册的设备');
    return;
  }
  const values = getFieldsValue();
  const creds = getValidCredentials();
  const timeout = Number(values.timeout) || 5;
  state.batchRegistering = true;
  state.registering = true;
  let okCount = 0;
  let failCount = 0;
  const isNvr = state.mode === 'nvr';
  try {
    for (let i = 0; i < list.length; i++) {
      const record = list[i];
      state.batchProgress = `正在处理 ${i + 1}/${list.length}：${record.ip}`;
      const ok = isNvr
        ? await registerOneNvr(record, creds, timeout, true)
        : await registerOneCamera(record, creds, timeout, true);
      if (ok) okCount += 1;
      else failCount += 1;
    }
    if (okCount > 0) emit('success');
    const action = isNvr ? '登记' : '注册';
    if (failCount === 0) createMessage.success(`批量${action}完成：成功 ${okCount} 台`);
    else createMessage.warning(`批量${action}完成：成功 ${okCount} 台，失败 ${failCount} 台`);
  } finally {
    state.batchRegistering = false;
    state.registering = false;
    state.batchProgress = '';
  }
}
</script>

<style lang="less" scoped>
:deep(.segment-scan-targets) {
  .ant-input {
    overflow: hidden !important;
    resize: none;
  }
}

:deep(.segment-scan-credentials) {
  .ant-form-item-control-input-content {
    max-width: 560px;
  }
}

.credentials-block {
  width: 100%;
}

.credential-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;

  .cred-user,
  .cred-pass {
    flex: 1;
    min-width: 0;
  }
}

:deep(.ant-spin-nested-loading),
:deep(.ant-spin-container) {
  height: 100%;
  min-height: 0;
}
</style>
