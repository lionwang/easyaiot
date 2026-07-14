<template>
  <BasicDrawer
    v-bind="$attrs"
    @register="registerDrawer"
    @open-change="handleOpenChange"
    width="1320"
    placement="right"
    :showFooter="true"
    :showOkBtn="false"
    :showCancelBtn="false"
    destroy-on-close
    root-class-name="edge-setup-guide-drawer"
  >
    <template #title>
      <div class="setup-drawer-header">
        <div class="setup-drawer-header__main">
          <div class="setup-drawer-header__icon">
            <Icon icon="mdi:server-network" :size="22" />
          </div>
          <div>
            <BasicTitle span class="setup-drawer-header__title">边缘节点接入</BasicTitle>
            <div class="setup-drawer-header__meta">
              {{ headerMeta }}
            </div>
          </div>
        </div>
        <div v-if="focusNode" class="setup-drawer-header__tags">
          <a-tag :color="statusColor(focusNode.status)">{{ statusText(focusNode.status) }}</a-tag>
          <a-tag :color="focusNode.cephMountReady ? 'success' : 'warning'">
            Ceph {{ focusNode.cephMountReady ? '就绪' : '未就绪' }}
          </a-tag>
        </div>
      </div>
    </template>

    <template #footer>
      <div class="footer-buttons">
        <Button @click="handleClose">
          {{ activeStepKey === 'finish' && verifiedOnline ? '完成' : '关闭' }}
        </Button>
        <div class="footer-nav">
          <Button v-if="!isFirstStep" @click="handlePrev">上一步</Button>
          <Button v-if="!isLastStep" type="primary" @click="handleNext">下一步</Button>
          <Button
            v-else
            type="primary"
            @click="handleGoAlgorithm"
          >
            去创建算法任务
          </Button>
        </div>
      </div>
    </template>

    <div class="setup-drawer-content">
      <div class="setup-hero">
        <div class="setup-hero__copy">
          <div class="setup-hero__eyebrow">EasyAIoT 无限联邦边缘集群模式</div>
          <div class="setup-hero__title">一行命令，把普通开发板直接智能化</div>
          <div class="setup-hero__desc">
            内存占用约 512MB，Ceph 边缘 0 硬盘占用；可随点位铺开算力部署。写入控制面地址并启动后，节点自动登记、订阅任务，告警与事件汇聚上云。
            按下方步骤完成安装、配置、启动与验收。
          </div>
          <div class="setup-hero__tags">
            <span v-for="tag in heroTags" :key="tag" class="setup-hero__tag">{{ tag }}</span>
          </div>
        </div>
        <div class="setup-hero__visual">
          <img :src="NODE_IMAGE" alt="" />
        </div>
      </div>

      <div class="setup-steps-card">
        <Steps
          class="setup-steps"
          :current="currentStep"
          :items="stepItems"
          @change="handleStepChange"
        />
      </div>

      <div class="setup-content-card">
        <!-- 1 了解 -->
        <div v-show="activeStepKey === 'overview'" class="step-panel">
          <Alert
            type="info"
            show-icon
            message="边缘节点通过 CLI 自助登记，接入无限联邦边缘集群。与「工作节点 → SSH 部署监测代理」为不同接入方式，请勿混用。"
          />
          <div class="journey-grid">
            <div v-for="item in journeyCards" :key="item.title" class="journey-card">
              <div class="journey-card__index">{{ item.index }}</div>
              <div class="journey-card__title">{{ item.title }}</div>
              <div class="journey-card__desc">{{ item.desc }}</div>
            </div>
          </div>
          <CollapseContainer title="接入后能力" :can-expan="false">
            <ul class="bullet-list">
              <li>约 512MB 内存即可加入联邦集群，开发板 / 工控机随装随扩</li>
              <li>Ceph 边缘 0 硬盘占用：告警图写共享路径，不落本地业务盘</li>
              <li>节点登记至边缘管理表（<code>edge_node</code>），由中心统一调度</li>
              <li>通过 MQTT 接收算法启停指令，在本地执行推理并将告警汇聚上云</li>
            </ul>
          </CollapseContainer>
        </div>

        <!-- 2 中心准备 -->
        <div v-show="activeStepKey === 'prepare'" class="step-panel">
          <Alert
            type="warning"
            show-icon
            message="请先确认中心侧服务就绪，否则节点登记或调度会失败。"
          />
          <CollapseContainer title="中心侧检查项" :can-expan="false">
            <div class="checklist">
              <div v-for="item in prepareChecklist" :key="item.key" class="checklist__item">
                <CheckCircleFilled v-if="item.ok === true" class="ok" />
                <CloseCircleFilled v-else-if="item.ok === false" class="bad" />
                <Icon v-else icon="ant-design:info-circle-filled" :size="14" color="#faad14" />
                <div>
                  <div class="checklist__label">{{ item.label }}</div>
                  <div class="checklist__hint">{{ item.hint }}</div>
                </div>
              </div>
            </div>
          </CollapseContainer>
          <CollapseContainer title="Join Token" :can-expan="false">
            <p class="form-hint">
              生产环境建议在控制面配置 <code>easyaiot.edge.join-token</code>，边缘侧执行
              <code>python -m edge config set-join-token &lt;token&gt;</code>。
              测试环境可开启 <code>easyaiot.edge.allow-open-enroll=true</code>，此时仅需控制面地址。
            </p>
          </CollapseContainer>
        </div>

        <!-- 3 安装 -->
        <div v-show="activeStepKey === 'install'" class="step-panel">
          <Alert type="info" show-icon message="在边缘设备终端进入仓库 EDGE 目录，安装 Python 依赖。" />
          <CollapseContainer title="安装依赖" :can-expan="false">
            <div class="script-toolbar">
              <Button size="small" preIcon="ant-design:copy-outlined" @click="copyText(installCmd, '安装命令已复制')">
                复制命令
              </Button>
            </div>
            <pre class="script-block">{{ installCmd }}</pre>
          </CollapseContainer>
        </div>

        <!-- 4 配置 -->
        <div v-show="activeStepKey === 'config'" class="step-panel">
          <Alert
            :type="isLocalControlPlaneUrl(controlPlaneUrl) ? 'warning' : 'info'"
            show-icon
            :message="
              isLocalControlPlaneUrl(controlPlaneUrl)
                ? '当前为 localhost，边缘设备无法通过回环地址访问中心，请改为局域网可达 IP（端口 48080）。'
                : '请确认下方地址为边缘设备可访问的 iot-node Gateway 根地址。'
            "
          />
          <CollapseContainer title="控制面地址" :can-expan="false">
            <div class="url-row">
              <a-input
                v-model:value="controlPlaneUrl"
                placeholder="http://<控制面主机>:48080"
                allow-clear
              />
              <Button @click="refreshControlPlaneUrl" :loading="resolvingUrl">重新探测</Button>
              <Button
                type="primary"
                preIcon="ant-design:copy-outlined"
                @click="copyText(controlPlaneUrl, '控制面地址已复制')"
              >
                复制
              </Button>
            </div>
            <p class="form-hint">
              对应环境变量 <code>EDGE_NODE_URL</code>。不要附加
              <code>/admin-api/node/agent</code> 等路径；Gateway 默认端口
              <code>48080</code>。
            </p>
            <div class="script-toolbar" style="margin-top: 12px">
              <Button size="small" preIcon="ant-design:copy-outlined" @click="copyText(setNodeCmd, '配置命令已复制')">
                复制配置命令
              </Button>
            </div>
            <pre class="script-block">{{ setNodeCmd }}</pre>
            <div class="join-token-block">
              <div class="join-token-block__label">Join Token（生产可选）</div>
              <a-input
                v-model:value="joinToken"
                placeholder="与控制面 easyaiot.edge.join-token 一致"
                allow-clear
              />
              <div class="script-toolbar" style="margin-top: 8px">
                <Button
                  size="small"
                  preIcon="ant-design:copy-outlined"
                  :disabled="!joinToken.trim()"
                  @click="copyText(setJoinTokenCmd, 'Join Token 命令已复制')"
                >
                  复制 set-join-token
                </Button>
              </div>
              <pre v-if="joinToken.trim()" class="script-block">{{ setJoinTokenCmd }}</pre>
            </div>
          </CollapseContainer>
        </div>

        <!-- 5 运行 -->
        <div v-show="activeStepKey === 'run'" class="step-panel">
          <Alert
            type="info"
            show-icon
            message="执行 python -m edge run：未登记时会自动 enroll，随后订阅 MQTT 任务指令。"
          />
          <CollapseContainer title="启动 Agent" :can-expan="false">
            <div class="script-toolbar">
              <Button size="small" preIcon="ant-design:copy-outlined" @click="copyText(runCmd, '启动命令已复制')">
                复制
              </Button>
            </div>
            <pre class="script-block">{{ runCmd }}</pre>
          </CollapseContainer>
          <CollapseContainer title="分步执行" :can-expan="false">
            <div class="script-toolbar">
              <Button size="small" preIcon="ant-design:copy-outlined" @click="copyText(stepRunCmd, '分步命令已复制')">
                复制
              </Button>
            </div>
            <pre class="script-block">{{ stepRunCmd }}</pre>
          </CollapseContainer>
          <CollapseContainer title="常用运维命令" :can-expan="false">
            <pre class="script-block">{{ opsCmd }}</pre>
          </CollapseContainer>
        </div>

        <!-- 6 验收 -->
        <div v-show="activeStepKey === 'verify'" class="step-panel">
          <Alert
            :type="verifiedOnline ? 'success' : 'info'"
            show-icon
            :message="
              verifiedOnline
                ? '已检测到在线边缘节点，可调整容量或创建算法任务。'
                : '请保持边缘侧 agent 运行，点击「刷新验收」确认节点出现并处于在线状态。'
            "
          />
          <CollapseContainer title="验收结果" :can-expan="false">
            <div class="verify-toolbar">
              <Button type="primary" :loading="verifying" preIcon="ant-design:reload-outlined" @click="runVerify">
                刷新验收
              </Button>
              <span class="form-hint" style="margin: 0">最近刷新：{{ lastVerifyAt || '尚未验收' }}</span>
            </div>
            <div class="checklist" style="margin-top: 16px">
              <div v-for="item in verifyChecklist" :key="item.key" class="checklist__item">
                <CheckCircleFilled v-if="item.ok" class="ok" />
                <CloseCircleFilled v-else class="bad" />
                <div>
                  <div class="checklist__label">{{ item.label }}</div>
                  <div class="checklist__hint">{{ item.hint }}</div>
                </div>
              </div>
            </div>
            <a-table
              v-if="recentNodes.length"
              style="margin-top: 16px"
              size="small"
              row-key="id"
              :pagination="false"
              :columns="verifyColumns"
              :data-source="recentNodes"
              :custom-row="(record) => ({ onClick: () => selectFocusNode(record) })"
            />
          </CollapseContainer>
          <CollapseContainer v-if="focusNode" title="节点配置" :can-expan="false">
            <a-form layout="vertical" class="edit-inline">
              <a-row :gutter="16">
                <a-col :span="8">
                  <a-form-item label="显示名称">
                    <a-input v-model:value="editForm.name" />
                  </a-form-item>
                </a-col>
                <a-col :span="8">
                  <a-form-item label="最大任务数">
                    <a-input-number v-model:value="editForm.maxTaskCount" :min="1" :max="64" style="width: 100%" />
                  </a-form-item>
                </a-col>
                <a-col :span="8">
                  <a-form-item label="启用">
                    <a-switch
                      :checked="editForm.enabled"
                      checked-children="启"
                      un-checked-children="停"
                      @change="(v) => (editForm.enabled = !!v)"
                    />
                  </a-form-item>
                </a-col>
              </a-row>
              <a-form-item label="备注">
                <a-textarea v-model:value="editForm.remark" :rows="2" />
              </a-form-item>
              <Button type="primary" :loading="saving" @click="saveFocusNode">保存配置</Button>
            </a-form>
          </CollapseContainer>
        </div>

        <!-- 7 完成 -->
        <div v-show="activeStepKey === 'finish'" class="step-panel">
          <Alert
            type="success"
            show-icon
            message="节点已加入无限联邦边缘集群。可在算法任务中指定该节点，现场推理结果将汇聚上云。"
          />
          <div class="finish-grid">
            <div class="finish-card">
              <div class="finish-card__title">联邦调度</div>
              <div class="finish-card__desc">
                新建算法任务时可指定边缘节点，或使用 auto / random 由平台选择在线且 Ceph 就绪的节点。
              </div>
            </div>
            <div class="finish-card">
              <div class="finish-card__title">铺开扩容</div>
              <div class="finish-card__desc">
                约 512MB 即可再接入一台开发板；调整最大任务数控制容量，停用后不再参与调度。
              </div>
            </div>
            <div class="finish-card">
              <div class="finish-card__title">汇聚上云</div>
              <div class="finish-card__desc">
                将 <code>python -m edge run</code> 配为 systemd 常驻；告警写共享 Ceph（边缘 0 硬盘），经 MQTT 回中心归档。
              </div>
            </div>
          </div>
          <div class="finish-actions">
            <Button type="primary" size="large" preIcon="ant-design:rocket-outlined" @click="handleGoAlgorithm">
              创建算法任务
            </Button>
            <Button size="large" @click="emitSuccessAndClose">返回列表</Button>
          </div>
        </div>
      </div>
    </div>
  </BasicDrawer>
</template>

<script lang="ts" setup>
import { computed, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import {
  Alert,
  Col as ACol,
  Form as AForm,
  FormItem as AFormItem,
  Input as AInput,
  InputNumber as AInputNumber,
  Row as ARow,
  Switch as ASwitch,
  Table as ATable,
  Tag as ATag,
  Textarea as ATextarea,
  Steps,
} from 'ant-design-vue';
import { CheckCircleFilled, CloseCircleFilled } from '@ant-design/icons-vue';
import { BasicDrawer, useDrawerInner } from '@/components/Drawer';
import { BasicTitle } from '@/components/Basic';
import { CollapseContainer } from '@/components/Container';
import { Button } from '@/components/Button';
import { Icon } from '@/components/Icon';
import { useMessage } from '@/hooks/web/useMessage';
import { copyText } from '@/utils/copyTextToClipboard';
import {
  getEdgeNode,
  getEdgeNodePage,
  updateEdgeNode,
  type EdgeNodeVO,
} from '@/api/device/edge';
import {
  getControlPlaneHookEndpoint,
  isLocalControlPlaneUrl,
  resolveControlPlaneAgentUrl,
} from '@/views/node/utils/constants';
import NODE_COMPUTE_IMAGE from '@/assets/images/node/node-compute.svg';
import { statusColor, statusText } from './Data';

defineOptions({ name: 'EdgeSetupGuideDrawer' });

const emit = defineEmits(['register', 'success', 'goto-algorithm']);

const EDGE_NODE_URL_KEY = 'easyaiot_edge_node_url';
const NODE_IMAGE = NODE_COMPUTE_IMAGE;
const { createMessage } = useMessage();
const router = useRouter();

type StepKey = 'overview' | 'prepare' | 'install' | 'config' | 'run' | 'verify' | 'finish';

interface StepDef {
  key: StepKey;
  title: string;
  description: string;
}

const STEPS: StepDef[] = [
  { key: 'overview', title: '概述', description: '接入方式说明' },
  { key: 'prepare', title: '中心准备', description: '服务与凭证' },
  { key: 'install', title: '安装依赖', description: '边缘侧环境' },
  { key: 'config', title: '配置地址', description: '控制面 URL' },
  { key: 'run', title: '启动', description: '登记与订阅' },
  { key: 'verify', title: '验收', description: '在线与 Ceph' },
  { key: 'finish', title: '完成', description: '任务调度' },
];

const currentStep = ref(0);
const controlPlaneUrl = ref('');
const joinToken = ref('');
const resolvingUrl = ref(false);
const verifying = ref(false);
const verifiedOnline = ref(false);
const lastVerifyAt = ref('');
const focusNode = ref<EdgeNodeVO | null>(null);
const recentNodes = ref<EdgeNodeVO[]>([]);
const saving = ref(false);

const editForm = reactive({
  id: 0,
  name: '',
  maxTaskCount: 1,
  remark: '',
  enabled: true,
});

const heroTags = ['内存约 512MB', 'Ceph 边缘 0 硬盘', '一行命令上线', '算力铺开部署', '汇聚上云', '无限联邦扩容'];

const journeyCards = [
  { index: '01', title: '中心就绪', desc: '确认 Gateway、MQTT、Ceph 可用；按需配置 Join Token' },
  { index: '02', title: '安装配置', desc: '约 512MB 即可起步，安装依赖并写入控制面地址' },
  { index: '03', title: '一行命令启动', desc: '运行 agent，开发板自动登记并加入联邦集群' },
  { index: '04', title: '验收上云', desc: '确认在线后创建算法任务，告警事件汇聚上云' },
];

const installCmd = `cd EDGE
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt`;

const opsCmd = `python -m edge status
python -m edge pull-config
python -m edge stop`;

const activeStepKey = computed(() => STEPS[currentStep.value]?.key ?? 'overview');
const isFirstStep = computed(() => currentStep.value === 0);
const isLastStep = computed(() => currentStep.value === STEPS.length - 1);
const stepItems = computed(() =>
  STEPS.map((s) => ({ title: s.title, description: s.description })),
);

const headerMeta = computed(() => {
  if (focusNode.value) {
    return `${focusNode.value.name || '未命名'} · ${focusNode.value.host || '-'}`;
  }
  return '无限联邦边缘集群 · 512MB内存 · Ceph 0 硬盘 · 汇聚上云';
});

const setNodeCmd = computed(
  () => `python -m edge config set-node ${controlPlaneUrl.value || 'http://<控制面主机>:48080'}`,
);

const setJoinTokenCmd = computed(
  () => `python -m edge config set-join-token ${joinToken.value.trim()}`,
);

const runCmd = computed(() => {
  const url = controlPlaneUrl.value || 'http://<控制面主机>:48080';
  const lines = [
    `# 尚未配置控制面地址时先执行：`,
    `python -m edge config set-node ${url}`,
  ];
  if (joinToken.value.trim()) {
    lines.push(`python -m edge config set-join-token ${joinToken.value.trim()}`);
  }
  lines.push(`python -m edge run`);
  return lines.join('\n');
});

const stepRunCmd = computed(() => {
  const lines = [setNodeCmd.value];
  if (joinToken.value.trim()) lines.push(setJoinTokenCmd.value);
  lines.push('python -m edge enroll', 'python -m edge run');
  return lines.join('\n');
});

const prepareChecklist = computed(() => [
  {
    key: 'gateway',
    label: 'iot-node Gateway 可达（:48080）',
    ok: !!controlPlaneUrl.value && !isLocalControlPlaneUrl(controlPlaneUrl.value) ? true : false,
    hint: controlPlaneUrl.value || '在「配置地址」步骤确认',
  },
  {
    key: 'mqtt',
    label: '中心 MQTT / EMQX 集群在线',
    ok: null as boolean | null,
    hint: 'runtime-config 会下发 broker 列表；请在节点管理中确认 MQTT 可用',
  },
  {
    key: 'ceph',
    label: 'CephFS 与中心路径一致',
    ok: null as boolean | null,
    hint: '边缘需挂载同一 Ceph 路径，否则 cephMountReady=false，任务不会调度到该节点',
  },
  {
    key: 'token',
    label: 'Join Token 或开放登记策略已确认',
    ok: null as boolean | null,
    hint: joinToken.value.trim()
      ? '已填写 Join Token，将写入配置命令'
      : '测试可用 allow-open-enroll；生产建议配置 join-token',
  },
]);

const verifyChecklist = computed(() => {
  const node = focusNode.value;
  return [
    {
      key: 'appear',
      label: '管理表出现节点记录',
      ok: recentNodes.value.length > 0,
      hint: recentNodes.value.length
        ? `当前可见 ${recentNodes.value.length} 条`
        : '暂无记录，请确认 agent 已启动且控制面地址正确',
    },
    {
      key: 'online',
      label: '节点状态为在线',
      ok: !!node && node.status === 'online',
      hint: node ? statusText(node.status) : '请点击列表行选中节点，或刷新验收',
    },
    {
      key: 'ceph',
      label: 'Ceph 挂载就绪',
      ok: !!node?.cephMountReady,
      hint: node?.cephMountReady ? '已挂载' : '未就绪时算法任务可能不会调度到该节点',
    },
    {
      key: 'heartbeat',
      label: '最近心跳正常',
      ok: !!node?.lastHeartbeatAt,
      hint: node?.lastHeartbeatAt || '暂无心跳',
    },
  ];
});

const verifyColumns = [
  { title: '名称', dataIndex: 'name', ellipsis: true },
  { title: '主机', dataIndex: 'host', width: 140 },
  {
    title: '状态',
    dataIndex: 'status',
    width: 90,
    customRender: ({ text }: { text: string }) => statusText(text),
  },
  {
    title: 'Ceph',
    dataIndex: 'cephMountReady',
    width: 90,
    customRender: ({ text }: { text: boolean }) => (text ? '就绪' : '未就绪'),
  },
  { title: '心跳', dataIndex: 'lastHeartbeatAt', width: 170 },
];

watch(controlPlaneUrl, (url) => {
  try {
    if (url?.trim() && !isLocalControlPlaneUrl(url)) {
      localStorage.setItem(EDGE_NODE_URL_KEY, url.trim());
    }
  } catch {
    /* ignore */
  }
});

const [registerDrawer, { closeDrawer }] = useDrawerInner(async (data?: { nodeId?: number; step?: StepKey }) => {
  currentStep.value = data?.step ? Math.max(0, STEPS.findIndex((s) => s.key === data.step)) : 0;
  if (currentStep.value < 0) currentStep.value = 0;
  verifiedOnline.value = false;
  lastVerifyAt.value = '';
  focusNode.value = null;
  recentNodes.value = [];
  await refreshControlPlaneUrl();
  if (data?.nodeId) {
    await loadFocusNode(data.nodeId);
    currentStep.value = STEPS.findIndex((s) => s.key === 'verify');
    await runVerify();
  }
});

async function refreshControlPlaneUrl() {
  resolvingUrl.value = true;
  try {
    let saved = '';
    try {
      saved = localStorage.getItem(EDGE_NODE_URL_KEY)?.trim() || '';
    } catch {
      /* ignore */
    }
    if (saved && !isLocalControlPlaneUrl(saved)) {
      controlPlaneUrl.value = saved;
      return;
    }
    await resolveControlPlaneAgentUrl();
    const { host, port } = getControlPlaneHookEndpoint();
    controlPlaneUrl.value = `http://${host}:${port}`;
  } finally {
    resolvingUrl.value = false;
  }
}

async function loadFocusNode(id: number) {
  try {
    const node = (await getEdgeNode(id)) as EdgeNodeVO;
    selectFocusNode(node);
  } catch (e: any) {
    createMessage.error(e?.message || '加载节点详情失败');
  }
}

function selectFocusNode(node: EdgeNodeVO) {
  focusNode.value = node;
  editForm.id = node.id || 0;
  editForm.name = node.name || '';
  editForm.maxTaskCount = node.maxTaskCount || 1;
  editForm.remark = node.remark || '';
  editForm.enabled = node.enabled !== false;
  if (node.status === 'online') verifiedOnline.value = true;
}

async function runVerify() {
  verifying.value = true;
  try {
    const res = await getEdgeNodePage({ pageNo: 1, pageSize: 20 });
    recentNodes.value = res?.list || [];
    lastVerifyAt.value = new Date().toLocaleString();
    if (focusNode.value?.id) {
      const matched = recentNodes.value.find((n) => n.id === focusNode.value?.id);
      if (matched) selectFocusNode(matched);
      else await loadFocusNode(focusNode.value.id);
    } else if (recentNodes.value.length) {
      const online = recentNodes.value.find((n) => n.status === 'online') || recentNodes.value[0];
      selectFocusNode(online);
    }
    if (recentNodes.value.some((n) => n.status === 'online')) {
      verifiedOnline.value = true;
      createMessage.success('已检测到在线边缘节点');
    } else if (recentNodes.value.length) {
      createMessage.warning('已有节点记录但未在线，请检查 agent 进程与网络');
    } else {
      createMessage.warning('暂无边缘节点记录，请确认 agent 已启动');
    }
  } catch (e: any) {
    createMessage.error(e?.message || '验收失败');
  } finally {
    verifying.value = false;
  }
}

async function saveFocusNode() {
  if (!editForm.id) return;
  saving.value = true;
  try {
    await updateEdgeNode({
      id: editForm.id,
      name: editForm.name,
      maxTaskCount: editForm.maxTaskCount,
      remark: editForm.remark,
      enabled: editForm.enabled,
    });
    createMessage.success('节点配置已保存');
    await runVerify();
    emit('success');
  } catch (e: any) {
    createMessage.error(e?.message || '保存失败');
  } finally {
    saving.value = false;
  }
}

function handlePrev() {
  if (!isFirstStep.value) currentStep.value -= 1;
}

function handleNext() {
  if (isLastStep.value) return;
  currentStep.value += 1;
  if (STEPS[currentStep.value]?.key === 'verify') void runVerify();
}

function handleStepChange(idx: number) {
  currentStep.value = idx;
  if (STEPS[idx]?.key === 'verify') void runVerify();
}

function handleClose() {
  closeDrawer();
}

function handleOpenChange(open: boolean) {
  if (!open) emit('success');
}

function handleGoAlgorithm() {
  emit('goto-algorithm');
  const { path, query } = router.currentRoute.value;
  router.push({ path, query: { ...query, tab: '7' } });
  closeDrawer();
}

function emitSuccessAndClose() {
  emit('success');
  closeDrawer();
}
</script>

<style lang="less" scoped>
@import '@/views/node/utils/setup-panel.less';

.setup-drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
  padding-right: 32px;
}

.setup-drawer-header__main {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.setup-drawer-header__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: linear-gradient(135deg, #eef4ff, #dce8ff);
  color: @node-primary;
  flex-shrink: 0;
}

.setup-drawer-header__title {
  font-size: 18px !important;
  font-weight: 600 !important;
}

.setup-drawer-header__meta {
  margin-top: 2px;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}

.setup-drawer-header__tags {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.setup-drawer-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 4px 0 8px;
}

.setup-hero {
  display: flex;
  align-items: stretch;
  justify-content: space-between;
  gap: 24px;
  padding: 22px 26px;
  border-radius: @setup-panel-radius;
  background: linear-gradient(120deg, #f3f7ff 0%, #ffffff 55%, #eef6ff 100%);
  border: 1px solid rgba(38, 108, 251, 0.12);
  box-shadow: @setup-panel-shadow;
}

.setup-hero__eyebrow {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: @node-primary;
  margin-bottom: 8px;
}

.setup-hero__title {
  font-size: 20px;
  font-weight: 600;
  color: #0f172a;
  line-height: 1.35;
}

.setup-hero__desc {
  margin-top: 10px;
  max-width: 720px;
  color: rgba(15, 23, 42, 0.65);
  font-size: 13px;
  line-height: 1.7;
}

.setup-hero__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
}

.setup-hero__tag {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  color: #1d4ed8;
  background: rgba(38, 108, 251, 0.08);
  border: 1px solid rgba(38, 108, 251, 0.14);
}

.setup-hero__visual {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;

  img {
    width: 108px;
    filter: drop-shadow(0 8px 16px rgba(67, 120, 154, 0.18));
  }
}

.setup-steps-card {
  padding: 16px 18px;
  border-radius: @setup-panel-radius;
  background: #fff;
  border: 1px solid rgba(0, 0, 0, 0.06);
  box-shadow: @setup-panel-shadow;
}

.setup-steps {
  :deep(.ant-steps-item) {
    flex: 1;
    min-width: 0;
  }

  :deep(.ant-steps-item-title) {
    font-size: 13px;
    font-weight: 500;
  }

  :deep(.ant-steps-item-description) {
    font-size: 12px;
  }

  :deep(.ant-steps-item-process .ant-steps-item-icon) {
    background: @node-primary;
    border-color: @node-primary;
  }
}

.step-panel {
  display: flex;
  flex-direction: column;
  gap: @setup-section-gap;
}

.journey-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.journey-card {
  padding: 16px 18px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid rgba(0, 0, 0, 0.06);
  box-shadow: @setup-panel-shadow;
  min-height: 132px;
}

.journey-card__index {
  font-size: 12px;
  font-weight: 700;
  color: @node-primary;
  letter-spacing: 0.06em;
}

.journey-card__title {
  margin-top: 8px;
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
}

.journey-card__desc {
  margin-top: 8px;
  font-size: 13px;
  line-height: 1.6;
  color: rgba(15, 23, 42, 0.6);
}

.bullet-list {
  margin: 0;
  padding-left: 18px;
  color: rgba(0, 0, 0, 0.75);
  line-height: 1.8;
}

.checklist {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.checklist__item {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 10px 12px;
  border-radius: 8px;
  background: #fafbfd;

  .ok {
    color: #52c41a;
    margin-top: 2px;
  }

  .bad {
    color: #ff4d4f;
    margin-top: 2px;
  }
}

.checklist__label {
  font-weight: 600;
  color: #0f172a;
}

.checklist__hint {
  margin-top: 2px;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
  line-height: 1.5;
}

.url-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.script-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 8px;
}

.script-block {
  margin: 0;
  padding: 14px 16px;
  border-radius: 8px;
  background: #0b1220;
  color: #e2e8f0;
  font-size: 12px;
  line-height: 1.7;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.join-token-block {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px dashed #f0f0f0;
}

.join-token-block__label {
  margin-bottom: 8px;
  font-weight: 600;
}

.verify-toolbar {
  display: flex;
  align-items: center;
  gap: 16px;
}

.edit-inline {
  max-width: 960px;
}

.finish-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.finish-card {
  padding: 18px 20px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid rgba(0, 0, 0, 0.06);
  box-shadow: @setup-panel-shadow;
}

.finish-card__title {
  font-size: 16px;
  font-weight: 600;
}

.finish-card__desc {
  margin-top: 8px;
  font-size: 13px;
  line-height: 1.7;
  color: rgba(0, 0, 0, 0.65);
}

.finish-actions {
  display: flex;
  gap: 12px;
  margin-top: 8px;
}

.footer-buttons {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
}

.footer-nav {
  display: flex;
  gap: 8px;
}

@media (max-width: 1100px) {
  .journey-grid,
  .finish-grid {
    grid-template-columns: 1fr 1fr;
  }

  .setup-hero {
    flex-direction: column;
  }
}
</style>

<style lang="less">
@import '@/views/node/utils/setup-panel.less';

.edge-setup-guide-drawer {
  .ant-drawer-header {
    padding: 16px 24px;
    border-bottom: 1px solid #f0f0f0;
  }

  .ant-drawer-body {
    background: linear-gradient(180deg, #f7f9fc 0%, #ffffff 140px);
  }

  .scrollbar__wrap {
    padding: 20px 24px !important;
  }

  .ant-drawer-footer {
    padding: 12px 24px;
    border-top: 1px solid #f0f0f0;
    background: #fff;
  }

  .xingyuv-collapse-container {
    .setup-section-card();
    padding: 0;
    overflow: hidden;

    .p-2 {
      padding: @setup-section-body-padding !important;
    }

    &__header {
      height: auto;
      min-height: 48px;
      padding: @setup-section-header-padding !important;
      border-bottom: 1px solid #f0f0f0;
    }
  }
}
</style>
