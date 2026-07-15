<template>
  <div class="product-script">
    <Alert
      type="info"
      show-icon
      message="标准 JSON（Alink / Topic Codec）可不配置脚本；仅私有二进制/厂商协议需要编写 rawDataToProtocol / protocolToRawData。"
      class="mb-12"
    />
    <Form layout="vertical">
      <FormItem label="启用脚本">
        <Switch v-model:checked="form.scriptEnabled" />
      </FormItem>
      <FormItem label="脚本内容">
        <Textarea
          v-model:value="form.scriptContent"
          :rows="16"
          placeholder="function rawDataToProtocol(topic, bytes) { return bytes; }&#10;function protocolToRawData(topic, message) { return JSON.stringify(message); }"
        />
      </FormItem>
      <Space>
        <Button @click="handleCheck" :loading="checking">校验语法</Button>
        <Button type="primary" @click="handleSave" :loading="saving">保存并热加载</Button>
      </Space>
    </Form>
  </div>
</template>

<script lang="ts" setup>
import { onMounted, reactive, ref, watch } from 'vue';
import { Alert, Button, Form, FormItem, Space, Switch, Textarea } from 'ant-design-vue';
import { useMessage } from '@/hooks/web/useMessage';
import { getProductScript, saveProductScript } from '@/api/device/product';
import { defHttp } from '@/utils/http/axios';

const props = defineProps({
  productId: { type: [Number, String], default: undefined },
  productIdentification: { type: String, default: '' },
});

const { createMessage } = useMessage();
const saving = ref(false);
const checking = ref(false);
const form = reactive({
  id: undefined as number | undefined,
  productId: undefined as number | undefined,
  productIdentification: '',
  scriptEnabled: false,
  scriptContent: '',
});

async function loadScript() {
  if (!props.productIdentification) return;
  try {
    const data = await getProductScript(props.productIdentification);
    if (data) {
      form.id = data.id;
      form.productId = data.productId;
      form.productIdentification = data.productIdentification;
      form.scriptEnabled = !!data.scriptEnabled;
      form.scriptContent = data.scriptContent || '';
    } else {
      form.id = undefined;
      form.productId = props.productId ? Number(props.productId) : undefined;
      form.productIdentification = props.productIdentification;
      form.scriptEnabled = false;
      form.scriptContent = '';
    }
  } catch (e) {
    console.warn('加载产品脚本失败', e);
  }
}

async function handleCheck() {
  checking.value = true;
  try {
    defHttp.setHeader({ 'X-Authorization': 'Bearer ' + localStorage.getItem('jwt_token') });
    await defHttp.post(
      { url: '/sink/product-script/check', data: { scriptContent: form.scriptContent } },
      { isTransformResponse: true },
    );
    createMessage.success('脚本校验通过');
  } catch (e: any) {
    createMessage.error(e?.message || '脚本校验失败');
  } finally {
    checking.value = false;
  }
}

async function handleSave() {
  if (!props.productIdentification) {
    createMessage.warning('缺少产品标识');
    return;
  }
  saving.value = true;
  try {
    await saveProductScript({
      id: form.id,
      productId: form.productId || (props.productId ? Number(props.productId) : undefined),
      productIdentification: props.productIdentification,
      scriptEnabled: form.scriptEnabled,
      scriptContent: form.scriptContent,
    });
    createMessage.success('脚本已保存');
    await loadScript();
  } catch (e: any) {
    createMessage.error(e?.message || '保存失败');
  } finally {
    saving.value = false;
  }
}

watch(
  () => props.productIdentification,
  () => loadScript(),
);

onMounted(loadScript);
</script>

<style scoped>
.product-script {
  padding: 8px 0;
}
.mb-12 {
  margin-bottom: 12px;
}
</style>
