<template>
  <div :class="['directory-manage-wrapper', { 'is-embedded': embedded }]">
    <div class="directory-layout">
      <!-- 左侧：目录树 -->
      <div class="directory-sidebar">
        <div class="sidebar-tree">
          <div class="tree-header">
            <div class="tree-header-button">
              <a-button type="primary" @click="handleAddDirectory">
                <template #icon>
                  <PlusOutlined />
                </template>
                添加目录
              </a-button>
              <a-button @click="handleOpenJsonEditor">
                <template #icon>
                  <Icon icon="ant-design:code-outlined" />
                </template>
                JSON 编辑
              </a-button>
            </div>
            <a-input
              v-model:value="directorySearchText"
              placeholder="请输入目录名称"
              allow-clear
              style="margin-top: 12px;"
            >
              <template #prefix>
                <Icon icon="ant-design:search-outlined" />
              </template>
            </a-input>
          </div>
          <div class="tree-content">
            <div
              v-for="dir in filteredDirectoryTree"
              :key="dir.id"
              class="tree-node"
            >
              <DirectoryTreeNode
                :directory="dir"
                :level="0"
                :expanded-keys="expandedKeys"
                :selected-id="selectedDirectoryId"
                @toggle="handleToggleNode"
                @select="handleSelectDirectory"
                @edit="handleEditDirectory"
                @delete="handleDeleteDirectory"
              />
            </div>
            <a-empty v-if="filteredDirectoryTree.length === 0" description="暂无目录" />
          </div>
        </div>
      </div>

      <!-- 右侧：设备列表 -->
      <div class="device-content">
        <div class="device-button-group">
          <a-button type="primary" :loading="syncCamerasLoading" @click="handleSyncCameras">
            <template #icon>
              <Icon icon="ant-design:cloud-sync-outlined" />
            </template>
            同步摄像头
          </a-button>
          <a-button
            :disabled="!selectedDirectoryId || !checkedKeys.length"
            @click="handleBatchMoveToDirectory"
          >
            <template #icon>
              <Icon icon="ant-design:folder-open-outlined" />
            </template>
            批量移动到目录
          </a-button>
        </div>
        <BasicTable @register="registerTable">
          <template #bodyCell="{ column, record }">
            <!-- 统一复制功能组件 -->
            <template v-if="column.key === 'name'">
              <span style="cursor: pointer" @click="handleCopy(record.name)">
                <Icon icon="tdesign:copy-filled" color="#4287FCFF"/>
                {{ formatCameraDeviceLabel(record) }}
              </span>
            </template>
            <template v-else-if="['id', 'model'].includes(column.key)">
              <span style="cursor: pointer" @click="handleCopy(record[column.key])">
                <Icon icon="tdesign:copy-filled" color="#4287FCFF"/> {{ record[column.key] }}
              </span>
            </template>

            <!-- 在线状态显示 -->
            <template v-else-if="column.dataIndex === 'online'">
              <a-tag :color="record.online ? 'green' : 'red'">
                {{ record.online ? '在线' : '离线' }}
              </a-tag>
            </template>

            <template v-else-if="column.dataIndex === 'action'">
              <TableAction :actions="getTableActions(record)" />
            </template>
          </template>
        </BasicTable>
      </div>
    </div>

    <!-- 目录编辑/创建模态框 -->
    <DirectoryModal
      @register="registerDirectoryModal"
      @success="handleDirectorySuccess"
    />

    <!-- JSON 编辑设备目录 -->
    <DirectoryJsonModal
      @register="registerJsonModal"
      @success="handleDirectorySuccess"
    />

    <MoveDevicesToDirectoryModal
      @register="registerMoveModal"
      @success="handleMoveDevicesSuccess"
    />
  </div>
</template>

<script lang="ts" setup>
import { ref, onMounted, computed } from 'vue';
import { useMessage } from '@/hooks/web/useMessage';
import { useModal } from '@/components/Modal';
import { BasicTable, TableAction, useTable } from '@/components/Table';
import { Icon } from '@/components/Icon';
import { Tag as ATag, Input as AInput, Empty as AEmpty } from 'ant-design-vue';
import {
  getDirectoryList,
  deleteDirectory,
  getDirectoryDevices,
  getStreamStatus,
  moveDeviceToDirectory,
  refreshDevices,
  syncGb28181Devices,
  type DeviceDirectory,
  type DeviceInfo,
  type StreamStatusResponse,
} from '@/api/device/camera';
import DirectoryModal from './DirectoryModal.vue';
import DirectoryJsonModal from './DirectoryJsonModal.vue';
import DirectoryTreeNode from './DirectoryTreeNode.vue';
import MoveDevicesToDirectoryModal from './MoveDevicesToDirectoryModal.vue';
import {
  PlusOutlined,
} from '@ant-design/icons-vue';
import { formatCameraDeviceLabel } from '@/views/camera/utils/deviceLabel';

withDefaults(
  defineProps<{
    /** 嵌入分屏监控配置态时减少外边距 */
    embedded?: boolean;
  }>(),
  { embedded: false },
);

const { createMessage } = useMessage();
const [registerDirectoryModal, { openModal: openDirectoryModal }] = useModal();
const [registerJsonModal, { openModal: openJsonModal }] = useModal();
const [registerMoveModal, { openModal: openMoveModal }] = useModal();

const checkedKeys = ref<string[]>([]);
const checkedRows = ref<DeviceInfo[]>([]);

// 目录相关
const selectedDirectoryId = ref<number | null>(null);
const selectedDirectoryName = ref<string>('');
const directoryTree = ref<DeviceDirectory[]>([]);
const expandedKeys = ref<Set<number>>(new Set());
const directorySearchText = ref<string>('');
const syncCamerasLoading = ref(false);

// 设备流状态映射
const deviceStreamStatuses = ref<Record<string, string>>({});

// 过滤目录树（根据搜索文本）
const filteredDirectoryTree = computed(() => {
  if (!directorySearchText.value) {
    return directoryTree.value;
  }
  
  const searchLower = directorySearchText.value.toLowerCase();
  
  const filterTree = (nodes: DeviceDirectory[]): DeviceDirectory[] => {
    return nodes
      .map(node => {
        const matches = node.name.toLowerCase().includes(searchLower);
        const filteredChildren = node.children ? filterTree(node.children) : [];
        
        if (matches || filteredChildren.length > 0) {
          return {
            ...node,
            children: filteredChildren.length > 0 ? filteredChildren : node.children,
          };
        }
        return null;
      })
      .filter((node): node is DeviceDirectory => node !== null);
  };
  
  return filterTree(directoryTree.value);
});

// 查找默认分组（根级）
const findDefaultDirectory = (nodes: DeviceDirectory[]): DeviceDirectory | null => {
  for (const node of nodes) {
    if (node.is_default) return node;
    if (node.children?.length) {
      const found = findDefaultDirectory(node.children);
      if (found) return found;
    }
  }
  return nodes.find((n) => n.name === '默认分组') || null;
};

// 收集所有目录ID（递归）
const collectDirectoryIds = (nodes: DeviceDirectory[]): Set<number> => {
  const ids = new Set<number>();
  const traverse = (dirs: DeviceDirectory[]) => {
    dirs.forEach(dir => {
      ids.add(dir.id);
      if (dir.children && dir.children.length > 0) {
        traverse(dir.children);
      }
    });
  };
  traverse(nodes);
  return ids;
};

// 加载目录列表
const loadDirectoryList = async () => {
  try {
    const response = await getDirectoryList();
    const data = response.code !== undefined ? response.data : response;
    
    if (data && Array.isArray(data)) {
      // 保存当前的展开状态
      const currentExpandedKeys = new Set(expandedKeys.value);
      const isInitialLoad = directoryTree.value.length === 0;
      
      directoryTree.value = data;
      
      const defaultDir = findDefaultDirectory(data);
      if (isInitialLoad) {
        if (defaultDir) {
          selectedDirectoryId.value = defaultDir.id;
          selectedDirectoryName.value = defaultDir.name;
          expandedKeys.value = new Set([defaultDir.id]);
          reloadDeviceTable();
        } else {
          expandedKeys.value = new Set();
        }
      } else {
        // 非首次加载时，保留当前展开状态，但清理掉已经不存在的目录的展开状态
        const validIds = collectDirectoryIds(data);
        const newExpandedKeys = new Set<number>();
        currentExpandedKeys.forEach(id => {
          if (validIds.has(id)) {
            newExpandedKeys.add(id);
          }
        });
        expandedKeys.value = newExpandedKeys;
      }
    } else {
      directoryTree.value = [];
      // 如果数据为空，清空展开状态
      if (directoryTree.value.length === 0) {
        expandedKeys.value = new Set();
      }
    }
  } catch (error) {
    console.error('加载目录列表失败', error);
    directoryTree.value = [];
  }
};

// 切换节点展开/折叠（手风琴效果）
const handleToggleNode = (directoryId: number, level: number) => {
  const newExpandedKeys = new Set(expandedKeys.value);
  
  if (newExpandedKeys.has(directoryId)) {
    // 折叠：移除当前节点及其所有子节点
    newExpandedKeys.delete(directoryId);
    removeChildrenKeys(directoryId, newExpandedKeys);
  } else {
    // 展开：如果是同一级的其他节点已展开，先折叠它们（手风琴效果）
    if (level === 0) {
      // 一级目录：折叠所有其他一级目录
      directoryTree.value.forEach(dir => {
        if (dir.id !== directoryId && newExpandedKeys.has(dir.id)) {
          newExpandedKeys.delete(dir.id);
          removeChildrenKeys(dir.id, newExpandedKeys);
        }
      });
    } else {
      // 二级或三级目录：找到同级节点并折叠
      const parent = findParentDirectory(directoryId, directoryTree.value);
      if (parent) {
        const siblings = parent.children || [];
        siblings.forEach(sibling => {
          if (sibling.id !== directoryId && newExpandedKeys.has(sibling.id)) {
            newExpandedKeys.delete(sibling.id);
            removeChildrenKeys(sibling.id, newExpandedKeys);
          }
        });
      }
    }
    
    // 展开当前节点
    newExpandedKeys.add(directoryId);
  }
  
  expandedKeys.value = newExpandedKeys;
};

// 移除节点的所有子节点的展开状态
const removeChildrenKeys = (parentId: number, keys: Set<number>) => {
  // 递归查找并移除所有子节点的展开状态
  const removeFromNode = (node: DeviceDirectory) => {
    if (node.children && node.children.length > 0) {
      node.children.forEach(child => {
        keys.delete(child.id);
        removeFromNode(child);
      });
    }
  };
  
  // 查找目标节点
  const findNode = (nodes: DeviceDirectory[], targetId: number): DeviceDirectory | null => {
    for (const node of nodes) {
      if (node.id === targetId) {
        return node;
      }
      if (node.children && node.children.length > 0) {
        const found = findNode(node.children, targetId);
        if (found) {
          return found;
        }
      }
    }
    return null;
  };
  
  const targetNode = findNode(directoryTree.value, parentId);
  if (targetNode) {
    removeFromNode(targetNode);
  }
};

// 查找父目录
const findParentDirectory = (childId: number, nodes: DeviceDirectory[]): DeviceDirectory | null => {
  for (const node of nodes) {
    if (node.children) {
      const found = node.children.find(child => child.id === childId);
      if (found) {
        return node;
      }
      const parent = findParentDirectory(childId, node.children);
      if (parent) {
        return parent;
      }
    }
  }
  return null;
};

// 选择目录
const handleSelectDirectory = (directory: DeviceDirectory) => {
  selectedDirectoryId.value = directory.id;
  selectedDirectoryName.value = directory.name;
  checkedKeys.value = [];
  checkedRows.value = [];
  reloadDeviceTable();
};

// 编辑目录
const handleEditDirectory = (directory: DeviceDirectory) => {
  openDirectoryModal(true, {
    type: 'edit',
    record: directory,
  });
};

// 删除目录
const handleDeleteDirectory = async (directory: DeviceDirectory) => {
  try {
    const response = await deleteDirectory(directory.id);
    const result = response.code !== undefined ? response : { code: 0, msg: '删除成功' };
    if (result.code === 0) {
      createMessage.success('删除成功');
      loadDirectoryList();
      // 如果删除的是当前选中的目录，清空选择
      if (selectedDirectoryId.value === directory.id) {
        selectedDirectoryId.value = null;
        selectedDirectoryName.value = '';
        reloadDeviceTable();
      }
      // 移除展开状态
      expandedKeys.value.delete(directory.id);
    } else {
      createMessage.error(result.msg || '删除失败');
    }
  } catch (error) {
    console.error('删除目录失败', error);
    createMessage.error('删除失败');
  }
};

// 获取设备表格列配置
const getDeviceColumns = () => {
  return [
    {
      title: '设备ID',
      dataIndex: 'id',
      width: 120,
    },
    {
      title: '设备名称',
      dataIndex: 'name',
      width: 120,
    },
    {
      title: '设备型号',
      dataIndex: 'model',
      width: 120,
    },
    {
      title: '在线状态',
      dataIndex: 'online',
      width: 100,
    },
    {
      title: '制造商',
      dataIndex: 'manufacturer',
      width: 90,
    },
    {
      title: 'IP地址',
      dataIndex: 'ip',
      width: 120,
    },
    {
      title: '端口',
      dataIndex: 'port',
      width: 80,
    },
    {
      title: '操作',
      dataIndex: 'action',
      width: 140,
      fixed: 'right',
    },
  ];
};

// 设备表格配置
const [registerTable, { reload: reloadDeviceTable }] = useTable({
  title: '设备列表',
  api: async (params) => {
    // 如果没有选择目录，返回空数据
    if (!selectedDirectoryId.value) {
      return { data: [], total: 0 };
    }

    try {
      const response = await getDirectoryDevices(selectedDirectoryId.value, {
        pageNo: params.pageNo || 1,
        pageSize: params.pageSize || 10,
        search: params.search || '',
        name: params.name || '',
        online: params.online !== undefined ? params.online : undefined,
        model: params.model || '',
      });
      
      const data = response.code !== undefined ? response.data : response;
      const total = response.code !== undefined ? response.total : (Array.isArray(data) ? data.length : 0);
      
      if (data && Array.isArray(data)) {
        // 应用筛选条件
        let filteredData = data;
        
        if (params.name) {
          filteredData = filteredData.filter((device: DeviceInfo) => 
            device.name && device.name.toLowerCase().includes(params.name.toLowerCase())
          );
        }
        
        if (params.online !== undefined && params.online !== '') {
          filteredData = filteredData.filter((device: DeviceInfo) => 
            device.online === params.online
          );
        }
        
        if (params.model) {
          filteredData = filteredData.filter((device: DeviceInfo) => 
            device.model && device.model.toLowerCase().includes(params.model.toLowerCase())
          );
        }
        
        // 初始化设备流状态
        const devicesWithStatus = filteredData.map((device: DeviceInfo) => {
          if (!deviceStreamStatuses.value[device.id]) {
            deviceStreamStatuses.value[device.id] = 'unknown';
          }
          return {
            ...device,
            stream_status: deviceStreamStatuses.value[device.id] || 'unknown',
          };
        });
        
        // 检查设备流状态
        // 已禁用自动检查设备流状态
        // checkAllDevicesStreamStatus(filteredData);
        
        return {
          data: devicesWithStatus,
          total: devicesWithStatus.length,
        };
      }
      return { data: [], total: 0 };
    } catch (error) {
      console.error('加载设备列表失败', error);
      return { data: [], total: 0 };
    }
  },
  fetchSetting: {
    listField: 'data',
    totalField: 'total',
  },
  columns: getDeviceColumns(),
  useSearchForm: true,
  formConfig: {
    labelWidth: 80,
    baseColProps: { span: 6 },
    actionColOptions: {
      span: 6,
      style: { textAlign: 'right' }
    },
    schemas: [
      {
        field: 'name',
        label: '设备名称',
        component: 'Input',
        componentProps: {
          placeholder: '请输入设备名称',
        },
      },
      {
        field: 'online',
        label: '在线状态',
        component: 'Select',
        componentProps: {
          placeholder: '请选择在线状态',
          allowClear: true,
          options: [
            { label: '在线', value: true },
            { label: '离线', value: false },
          ],
        },
      },
      {
        field: 'model',
        label: '设备型号',
        component: 'Input',
        componentProps: {
          placeholder: '请输入设备型号',
        },
      },
    ],
  },
  showTableSetting: true,
  pagination: true,
  rowKey: 'id',
  canResize: true,
  rowSelection: {
    type: 'checkbox',
    selectedRowKeys: checkedKeys,
    onChange: (keys: string[], rows: DeviceInfo[]) => {
      checkedKeys.value = keys;
      checkedRows.value = rows;
    },
  },
});

// 获取流状态文本
const getStreamStatusText = (status: string) => {
  const statusMap: Record<string, string> = {
    'running': '运行中',
    'stopped': '已停止',
    'error': '错误',
    'unknown': '未知'
  };
  return statusMap[status] || status || '未知';
};

// 获取流状态颜色
const getStreamStatusColor = (status: string) => {
  const colorMap: Record<string, string> = {
    'running': 'green',
    'stopped': 'red',
    'error': 'orange',
    'unknown': 'default'
  };
  return colorMap[status] || 'default';
};

// 安全获取设备流状态
const getDeviceStreamStatus = (deviceId: string) => {
  if (!deviceStreamStatuses.value || !deviceStreamStatuses.value[deviceId]) {
    return 'unknown';
  }
  return deviceStreamStatuses.value[deviceId];
};

// 检查单个设备的流状态
const checkDeviceStreamStatus = async (deviceId: string) => {
  try {
    if (!deviceStreamStatuses.value) {
      deviceStreamStatuses.value = {};
    }
    const response: StreamStatusResponse = await getStreamStatus(deviceId);
    if (response.code === 0) {
      deviceStreamStatuses.value[deviceId] = response.data.status;
    } else {
      deviceStreamStatuses.value[deviceId] = 'error';
    }
  } catch (error) {
    console.error(`检查设备 ${deviceId} 流状态失败`, error);
    if (!deviceStreamStatuses.value) {
      deviceStreamStatuses.value = {};
    }
    deviceStreamStatuses.value[deviceId] = 'error';
  }
};

// 检查所有设备的流状态
const checkAllDevicesStreamStatus = async (devices: DeviceInfo[]) => {
  try {
    const deviceIds = devices.map(device => device.id);
    for (const deviceId of deviceIds) {
      await checkDeviceStreamStatus(deviceId);
    }
  } catch (error) {
    console.error('检查设备流状态失败', error);
  }
};

const openMoveDevicesModal = (devices: DeviceInfo[]) => {
  if (!devices.length) {
    createMessage.warning('请先选择摄像头');
    return;
  }
  openMoveModal(true, {
    deviceIds: devices.map((d) => d.id),
  });
};

const handleBatchMoveToDirectory = () => {
  if (!selectedDirectoryId.value) {
    createMessage.warning('请先选择目录');
    return;
  }
  openMoveDevicesModal(checkedRows.value);
};

const handleMoveToDirectory = (record: DeviceInfo) => {
  openMoveDevicesModal([record]);
};

const handleMoveDevicesSuccess = () => {
  checkedKeys.value = [];
  checkedRows.value = [];
  loadDirectoryList();
  if (selectedDirectoryId.value) {
    reloadDeviceTable();
  }
};

// 获取表格操作按钮
const getTableActions = (record: DeviceInfo) => {
  const actions = [
    {
      icon: 'octicon:play-16',
      tooltip: '播放RTMP流',
      onClick: () => handlePlay(record)
    },
    {
      icon: 'ant-design:folder-open-outlined',
      tooltip: '移动到目录',
      onClick: () => handleMoveToDirectory(record),
    },
    {
      icon: 'ant-design:rollback-outlined',
      tooltip: '移回默认分组',
      popConfirm: {
        title: '确定将此设备移回默认分组？',
        confirm: () => handleUnbindDirectory(record)
      }
    },
  ];

  return actions;
};

// 播放
const handlePlay = (record: DeviceInfo) => {
  emit('play', record);
};

// 移回默认分组
const handleUnbindDirectory = async (record: DeviceInfo) => {
  try {
    createMessage.loading({ content: '正在移回默认分组...', key: 'unbind' });
    const response = await moveDeviceToDirectory(record.id, 0);
    const result = response.code !== undefined ? response : { code: 0, msg: '已移回默认分组' };
    if (result.code === 0) {
      createMessage.success({ content: '已移回默认分组', key: 'unbind' });
      reloadDeviceTable();
    } else {
      createMessage.error({ content: result.msg || '解除关联失败', key: 'unbind' });
    }
  } catch (error) {
    console.error('解除关联失败', error);
    createMessage.error({ content: '解除关联失败', key: 'unbind' });
  }
};

// 复制功能
async function handleCopy(text: string) {
  if (!text || text === '-') {
    return;
  }
  try {
    if (navigator.clipboard) {
      await navigator.clipboard.writeText(text);
    } else {
      const textarea = document.createElement('textarea');
      textarea.value = text;
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand('copy');
      document.body.removeChild(textarea);
    }
    createMessage.success('复制成功');
  } catch (error) {
    console.error('复制失败', error);
    createMessage.error('复制失败');
  }
}

/** 同步直连（ONVIF 刷新）与国标（WVP 通道入库）摄像头 */
const handleSyncCameras = async () => {
  const parts: string[] = [];
  try {
    syncCamerasLoading.value = true;
    createMessage.loading({ content: '正在同步摄像头...', key: 'sync-cameras' });

    try {
      await refreshDevices();
      parts.push('直连设备已刷新');
    } catch (error) {
      console.error('刷新直连设备失败', error);
      parts.push('直连设备刷新失败');
    }

    try {
      const response = await syncGb28181Devices();
      const payload = response?.code !== undefined ? response.data : response;
      const created = payload?.created ?? 0;
      const total = payload?.total_gb_devices ?? 0;
      parts.push(`国标新增 ${created} 个，共 ${total} 个`);
    } catch (error) {
      console.error('同步国标设备失败', error);
      parts.push('国标同步失败，请检查 WVP 服务');
    }

    createMessage.success({ content: parts.join('；'), key: 'sync-cameras' });
    await loadDirectoryList();
    if (selectedDirectoryId.value) {
      reloadDeviceTable();
    }
  } finally {
    syncCamerasLoading.value = false;
  }
};

// 添加目录
const handleAddDirectory = () => {
  openDirectoryModal(true, {
    type: 'create',
  });
};

// JSON 编辑设备目录
const handleOpenJsonEditor = () => {
  openJsonModal(true, {});
};

// 目录操作成功回调
const handleDirectorySuccess = () => {
  loadDirectoryList();
  if (selectedDirectoryId.value) {
    reloadDeviceTable();
  }
};

// 暴露事件
const emit = defineEmits(['view', 'edit', 'delete', 'play', 'toggleStream']);

// 暴露刷新方法
defineExpose({
  refresh: () => {
    loadDirectoryList();
    if (selectedDirectoryId.value) {
      reloadDeviceTable();
    }
  },
});

// 组件挂载时加载目录列表
onMounted(() => {
  loadDirectoryList();
});
</script>

<style lang="less" scoped>
.directory-manage-wrapper {
  padding: 16px;
  background: #f0f2f5;
  min-height: calc(100vh - 200px);
  height: 100%;

  &.is-embedded {
    padding: 0;
    background: transparent;
    min-height: calc(100vh - 260px);
  }
}

.directory-layout {
  display: flex;
  gap: 16px;
  height: 100%;
}

.directory-sidebar {
  width: 300px;
  flex-shrink: 0;
  background: #fff;
  border-radius: 4px;
  display: flex;
  flex-direction: column;
  overflow: hidden;

  .sidebar-tree {
    flex: 1;
    overflow: hidden;
    display: flex;
    flex-direction: column;
    padding: 16px;
    
    .tree-header {
      margin-bottom: 16px;
      
      .tree-header-button {
        display: flex;
        flex-wrap: wrap;
        justify-content: flex-end;
        gap: 8px;
      }
    }
    
    .tree-content {
      flex: 1;
      overflow-y: auto;
      overflow-x: hidden;
      
      .tree-node {
        margin-bottom: 0;
      }
    }
  }
}

.device-content {
  flex: 1;
  background: #fff;
  border-radius: 4px;
  padding: 16px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.device-button-group {
  margin-bottom: 16px;
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}
</style>
