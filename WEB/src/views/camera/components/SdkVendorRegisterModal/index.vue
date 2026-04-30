<template>
  <BasicModal
    v-bind="$attrs"
    @register="register"
    :title="modalTitle"
    width="560"
    @ok="handleSubmit"
    :confirmLoading="loading"
  >
    <BasicForm @register="registerForm" />
  </BasicModal>
</template>

<script lang="ts" setup>
import { computed, ref } from 'vue';
import { BasicForm, useForm } from '@/components/Form';
import { BasicModal, useModalInner } from '@/components/Modal';
import { useMessage } from '@/hooks/web/useMessage';
import {
  fetchHikPlatformCameras,
  type SdkVendor,
} from '@/api/device/iot_video_sdk';
import { registerDevice } from '@/api/device/camera';
import { ensureDeviceStreamForwardTask } from '@/api/device/stream_forward';

defineOptions({ name: 'SdkVendorRegisterModal' });

const emit = defineEmits(['success', 'register']);

const { createMessage } = useMessage();
const loading = ref(false);
const vendor = ref<SdkVendor>('dahua');

const modalTitle = computed(() =>
  vendor.value === 'dahua' ? '大华 SDK 批量注册' : '海康 SDK 批量注册',
);

const [registerForm, { validate, resetFields, setFieldsValue }] = useForm({
  labelWidth: 100,
  schemas: [
    {
      field: 'protocol',
      label: '协议',
      component: 'RadioButtonGroup',
      defaultValue: 'https',
      required: true,
      componentProps: {
        options: [
          { label: 'http', value: 'http' },
          { label: 'https', value: 'https' },
        ],
      },
    },
    {
      field: 'host',
      label: '平台地址',
      component: 'Input',
      required: true,
      rules: [{ required: true, message: '请输入平台地址（如 192.168.2.5:443）' }],
      componentProps: { placeholder: '例如：192.168.2.5:443' },
    },
    {
      field: 'appKey',
      label: 'App Key',
      component: 'Input',
      required: true,
      rules: [{ required: true, message: '请输入 App Key' }],
    },
    {
      field: 'appSecret',
      label: 'App Secret',
      component: 'InputPassword',
      required: true,
      rules: [{ required: true, message: '请输入 App Secret' }],
    },
  ],
  showActionButtonGroup: false,
});

const [register, { closeModal, setModalProps }] = useModalInner(async (data: { vendor: SdkVendor }) => {
  vendor.value = data?.vendor || 'dahua';
  setModalProps({ confirmText: '保存' });
  resetFields();
  setFieldsValue({
    protocol: 'https',
    host: '',
    appKey: '',
    appSecret: '',
  });
});

async function handleSubmit() {
  try {
    const values = await validate();
    loading.value = true;
    if (vendor.value !== 'hikvision') {
      createMessage.error('当前版本仅支持海康平台 AppKey/AppSecret 批量注册');
      return;
    }
    const sdkRes = await fetchHikPlatformCameras({
      protocol: values.protocol,
      host: String(values.host || '').trim(),
      appKey: values.appKey,
      appSecret: values.appSecret,
    });
    const raw = sdkRes as any;
    const httpCode = raw?.code;
    if (httpCode !== undefined && httpCode !== 200 && httpCode !== 0) {
      createMessage.error(raw?.msg || 'SDK 获取摄像头列表失败');
      return;
    }
    const info = (raw?.data ?? raw) as {
      host?: string;
      list?: Array<Record<string, unknown>>;
      count?: number;
    };
    if (!info || typeof info !== 'object' || !Array.isArray(info.list)) {
      createMessage.error('SDK 返回数据异常');
      return;
    }
    if (info.list.length === 0) {
      createMessage.warning('平台未返回可注册摄像头');
      return;
    }

    let successCount = 0;
    let failCount = 0;
    for (const camera of info.list) {
      try {
        const cameraIndexCode = String(camera.cameraIndexCode || '').trim();
        if (!cameraIndexCode) {
          failCount += 1;
          continue;
        }
        const name = String(camera.cameraName || camera.name || '').trim() || `HIK-${cameraIndexCode}`;
        const registerPayload: Parameters<typeof registerDevice>[0] = {
          name,
          source: `hikvision://${cameraIndexCode}`,
          cameraType: 'hikvision',
          manufacturer: '海康威视',
          model: String(camera.cameraType || 'Hikvision-Platform-Camera'),
          serial_number: String(camera.cameraCode || cameraIndexCode),
          hardware_id: cameraIndexCode,
        };
        const reg = await registerDevice(registerPayload as any);
        const deviceId = (reg as any)?.data?.id;
        if (deviceId) {
          try {
            await ensureDeviceStreamForwardTask(deviceId);
          } catch {
            // 不影响主流程
          }
        }
        successCount += 1;
      } catch {
        failCount += 1;
      }
    }

    if (successCount > 0) {
      createMessage.success(`批量注册完成：成功 ${successCount} 台，失败 ${failCount} 台`);
      closeModal();
      emit('success');
      return;
    }
    createMessage.error(`批量注册失败：成功 0 台，失败 ${failCount} 台`);
  } catch (e: any) {
    createMessage.error(e?.message || e?.msg || '操作失败');
  } finally {
    loading.value = false;
  }
}
</script>
