<template>
  <div class="onvif-scan-wrap">
    <Tabs v-model:activeKey="subKey" type="card">
      <TabPane key="task" tab="扫描任务">
        <OnvifScanTaskPanel ref="taskPanelRef" />
      </TabPane>
      <TabPane key="pwd" tab="密码库">
        <OnvifPasswordLibraryPanel ref="pwdPanelRef" />
      </TabPane>
      <TabPane key="srv" tab="服务器管理">
        <OnvifServerManagePanel ref="srvPanelRef" />
      </TabPane>
      <TabPane key="blk" tab="IP黑名单">
        <OnvifIpBlacklistPanel ref="blkPanelRef" />
      </TabPane>
    </Tabs>
  </div>
</template>

<script lang="ts" setup>
import { ref } from 'vue';
import { TabPane, Tabs } from 'ant-design-vue';
import OnvifScanTaskPanel from './OnvifScanTaskPanel.vue';
import OnvifPasswordLibraryPanel from './OnvifPasswordLibraryPanel.vue';
import OnvifServerManagePanel from './OnvifServerManagePanel.vue';
import OnvifIpBlacklistPanel from './OnvifIpBlacklistPanel.vue';

defineOptions({ name: 'OnvifScan' });

const subKey = ref('task');

const taskPanelRef = ref<InstanceType<typeof OnvifScanTaskPanel> | null>(null);
const pwdPanelRef = ref<InstanceType<typeof OnvifPasswordLibraryPanel> | null>(null);
const srvPanelRef = ref<InstanceType<typeof OnvifServerManagePanel> | null>(null);
const blkPanelRef = ref<InstanceType<typeof OnvifIpBlacklistPanel> | null>(null);

/** 切换到此 Tab 时刷新各子面板表格数据 */
function refresh() {
  taskPanelRef.value?.refresh?.();
  pwdPanelRef.value?.refresh?.();
  srvPanelRef.value?.refresh?.();
  blkPanelRef.value?.refresh?.();
}

defineExpose({ refresh });
</script>

<style scoped>
.onvif-scan-wrap {
  padding: 8px 4px 0;
}
</style>
