import {defHttp} from '@/utils/http/axios';

const CAMERA_PREFIX = '/video/camera';

// 通用请求封装
const commonApi = (method: 'get' | 'post' | 'delete' | 'put', url: string, params = {}, headers = {}, isTransformResponse = true) => {
  defHttp.setHeader({ 'X-Authorization': 'Bearer ' + localStorage.getItem('jwt_token') });

  return defHttp[method]({
    url,
    headers: { ...headers },
    ...(method === 'get' ? { params } : { data: params })
  }, { isTransformResponse: isTransformResponse });
};

// ====================== 流媒体转发接口 ======================
/**
 * 启动FFmpeg转发RTSP流到RTMP服务器
 * @param device_id 设备ID
 * @returns 包含RTMP URL和进程ID的响应
 */
export const startStreamForwarding = (device_id: string) => {
  return commonApi('post', `${CAMERA_PREFIX}/device/${device_id}/stream/start`, {}, {}, false);
};

/**
 * 停止FFmpeg转发进程
 * @param device_id 设备ID
 * @returns 操作结果
 */
export const stopStreamForwarding = (device_id: string) => {
  return commonApi('post', `${CAMERA_PREFIX}/device/${device_id}/stream/stop`, {}, {}, false);
};

/**
 * 获取FFmpeg转发状态
 * @param device_id 设备ID
 * @returns 包含状态、RTMP URL和进程信息的响应
 */
export const getStreamStatus = (device_id: string) => {
  return commonApi('get', `${CAMERA_PREFIX}/device/${device_id}/stream/status`);
};

/**
 * 批量获取设备流媒体转发状态
 * @param device_ids 设备ID数组
 * @returns 包含所有设备流媒体状态的响应
 */
export const getBatchStreamStatus = (device_ids: string[]) => {
  return Promise.all(device_ids.map(id => getStreamStatus(id)));
};

// ====================== 设备管理接口 ======================
export const registerDevice = (data: {
  id?: string;
  name: string;
  ip?: string;
  port?: number;
  username?: string;
  password?: string;
  source?: string;
  cameraType?: string;
  stream?: number;
  enable_forward?: boolean;
  rtmp_stream?: string;
  http_stream?: string;
  ai_rtmp_stream?: string;
  ai_http_stream?: string;
  manufacturer?: string;
  model?: string;
  serial_number?: string;
  hardware_id?: string;
}) => {
  return commonApi('post', `${CAMERA_PREFIX}/register/device`, data);
};

/**
 * 通过ONVIF搜索并自动注册摄像头
 * @param data 包含IP、端口、密码的对象
 * @returns 注册结果
 */
export const registerDeviceByOnvif = (data: {
  ip: string;
  port: number;
  password: string;
}) => {
  return commonApi('post', `${CAMERA_PREFIX}/register/device/onvif`, data);
};

export const getDeviceInfo = (device_id: string) => {
  return commonApi('get', `${CAMERA_PREFIX}/device/${device_id}`);
};

export const updateDevice = (device_id: string, data: {
  name?: string;
  ip?: string;
  port?: number;
  username?: string;
  password?: string;
  source?: string;
  cameraType?: string;
  stream?: number;
  enable_forward?: boolean;
  rtmp_stream?: string;
  http_stream?: string;
  ai_rtmp_stream?: string;
  ai_http_stream?: string;
  manufacturer?: string;
  model?: string;
  serial_number?: string;
  hardware_id?: string;
}) => {
  return commonApi('put', `${CAMERA_PREFIX}/device/${device_id}`, data);
};

export const deleteDevice = (device_id: string) => {
  return commonApi('delete', `${CAMERA_PREFIX}/device/${device_id}`);
};

export const getDeviceList = (params: {
  pageNo?: number;
  pageSize?: number;
  search?: string;
  enable_forward?: boolean;
}) => {
  return commonApi('get', `${CAMERA_PREFIX}/list`, params);
};

export const getDeviceStatus = () => {
  return commonApi('get', `${CAMERA_PREFIX}/device/status`);
};

// ====================== PTZ控制接口 ======================
export const controlPTZ = (device_id: string, data: {
  x: number;
  y: number;
  z: number;
}) => {
  return commonApi('post', `${CAMERA_PREFIX}/device/${device_id}/ptz`, data, {}, false);
};

// ====================== 截图任务接口 ======================
export const startRtspCapture = (device_id: number, data: {
  rtsp_url?: string;
  interval?: number;
  max_count?: number;
}) => {
  return commonApi('post', `${CAMERA_PREFIX}/device/${device_id}/rtsp/start`, data);
};

export const stopRtspCapture = (device_id: number) => {
  return commonApi('post', `${CAMERA_PREFIX}/device/${device_id}/rtsp/stop`);
};

export const getRtspStatus = (device_id: number) => {
  return commonApi('get', `${CAMERA_PREFIX}/device/${device_id}/rtsp/status`);
};

export const startOnvifCapture = (device_id: number, data: {
  interval?: number;
  max_count?: number;
}) => {
  return commonApi('post', `${CAMERA_PREFIX}/device/${device_id}/onvif/start`, data);
};

export const stopOnvifCapture = (device_id: number) => {
  return commonApi('post', `${CAMERA_PREFIX}/device/${device_id}/onvif/stop`);
};

export const getOnvifStatus = (device_id: number) => {
  return commonApi('get', `${CAMERA_PREFIX}/device/${device_id}/onvif/status`);
};

export const getOnvifProfiles = (device_ip: string, device_port: number, auth: {
  username: string;
  password: string;
}) => {
  return commonApi('post', `${CAMERA_PREFIX}/device/onvif/${device_ip}/${device_port}/profiles`, auth);
};

// ====================== 设备发现接口 ======================
export const discoverDevices = () => {
  defHttp.setHeader({ 'X-Authorization': 'Bearer ' + localStorage.getItem('jwt_token') });
  return defHttp.get(
    {
      url: `${CAMERA_PREFIX}/discovery`,
      timeout: 120 * 1000,
    },
    { isTransformResponse: true },
  );
};

export const refreshDevices = () => {
  return commonApi('post', `${CAMERA_PREFIX}/refresh`);
};

// ====================== MinIO上传接口 ======================
export const uploadScreenshot = (formData: FormData) => {
  return defHttp.post({
    url: `${CAMERA_PREFIX}/upload`,
    data: formData,
    headers: {
      'Content-Type': 'multipart/form-data',
      'X-Authorization': 'Bearer ' + localStorage.getItem('jwt_token')
    }
  });
};

// ====================== 类型定义 ======================
export interface StreamStatusResponse {
  code: number;
  msg: string;
  data: {
    status: 'running' | 'stopped';
    rtmp_url: string | null;
    enable_forward: boolean;
    pid?: number;
    start_time?: string;
  };
}

export interface StartStreamResponse {
  code: number;
  msg: string;
  data: {
    rtmp_url: string;
    process_id: number;
  };
}

export interface DeviceInfo {
  id: string;
  name: string;
  source: string;
  rtmp_stream: string;
  http_stream: string;
  ai_rtmp_stream?: string;
  ai_http_stream?: string;
  stream: number;
  ip: string;
  port: number;
  username: string;
  password: string;
  mac: string;
  manufacturer: string;
  model: string;
  firmware_version: string;
  serial_number: string;
  hardware_id: string;
  support_move: boolean;
  support_zoom: boolean;
  enable_forward: boolean;
  cover_image_path?: string;
  created_at: string;
  updated_at: string;
}

export interface DeviceListResponse {
  code: number;
  msg: string;
  data: DeviceInfo[];
  total: number;
}

// ====================== 设备目录管理接口 ======================
export interface DeviceDirectory {
  id: number;
  name: string;
  parent_id: number | null;
  description?: string;
  sort_order: number;
  device_count?: number;
  is_default?: boolean;
  children?: DeviceDirectory[];
  created_at?: string;
  updated_at?: string;
}

export interface DirectoryListResponse {
  code: number;
  msg: string;
  data: DeviceDirectory[];
}

/** 分屏监控树 - 设备节点 */
export interface MonitorTreeDeviceNode {
  type: 'device';
  id: string;
  name: string;
  http_stream?: string;
  rtmp_stream?: string;
  ai_http_stream?: string;
  ai_rtmp_stream?: string;
  online?: boolean;
  directory_id?: number | null;
  device_kind?: 'direct' | 'gb28181';
  source?: string | null;
}

/** 分屏监控树 - 目录节点 */
export interface MonitorTreeDirectoryNode {
  type: 'directory';
  id: number;
  name: string;
  parent_id?: number | null;
  sort_order?: number;
  device_count?: number;
  is_default?: boolean;
  children: MonitorTreeDirectoryNode[];
  devices: MonitorTreeDeviceNode[];
}

export interface DirectoryMonitorTreeData {
  tree: MonitorTreeDirectoryNode[];
  unassigned_devices: MonitorTreeDeviceNode[];
}

export interface DirectoryMonitorTreeResponse {
  code: number;
  msg: string;
  data: DirectoryMonitorTreeData;
}

export interface DirectoryInfoResponse {
  code: number;
  msg: string;
  data: {
    id: number;
    name: string;
    parent_id: number | null;
    description?: string;
    sort_order: number;
    device_count: number;
    children_count: number;
    created_at?: string;
    updated_at?: string;
  };
}

/**
 * 获取目录列表（树形结构）
 */
export const getDirectoryList = () => {
  return commonApi('get', `${CAMERA_PREFIX}/directory/list`);
};

/**
 * 获取分屏监控用目录设备树（目录 + 设备，单次请求）
 */
export const getDirectoryMonitorTree = () => {
  return commonApi('get', `${CAMERA_PREFIX}/directory/monitor-tree`);
};

export interface SyncGb28181DevicesResult {
  created: number;
  total_gb_devices: number;
}

/**
 * 从 WVP 手动同步国标通道到设备目录（默认分组）
 */
export const syncGb28181Devices = () => {
  return commonApi('post', `${CAMERA_PREFIX}/directory/sync-gb28181`, {}, {}, false);
};

/** 校验设备目录 JSON（摄像头不可重复等） */
export const validateDirectoryJson = (tree: unknown[]) => {
  return commonApi('post', `${CAMERA_PREFIX}/directory/validate-json`, { tree }, {}, false);
};

/** 按 JSON 同步设备目录（服务端校验并写入） */
export const syncDirectoryFromJson = (tree: unknown[]) => {
  return commonApi('post', `${CAMERA_PREFIX}/directory/sync-json`, { tree }, {}, false);
};

/**
 * 获取目录详情
 * @param directory_id 目录ID
 */
export const getDirectoryInfo = (directory_id: number) => {
  return commonApi('get', `${CAMERA_PREFIX}/directory/${directory_id}`);
};

/**
 * 创建目录
 * @param data 目录信息
 */
export const createDirectory = (data: {
  name: string;
  parent_id?: number | null;
  description?: string;
  sort_order?: number;
}) => {
  return commonApi('post', `${CAMERA_PREFIX}/directory`, data);
};

/**
 * 更新目录
 * @param directory_id 目录ID
 * @param data 目录信息
 */
export const updateDirectory = (directory_id: number, data: {
  name?: string;
  parent_id?: number | null;
  description?: string;
  sort_order?: number;
}) => {
  return commonApi('put', `${CAMERA_PREFIX}/directory/${directory_id}`, data);
};

/**
 * 删除目录
 * @param directory_id 目录ID
 */
export const deleteDirectory = (directory_id: number) => {
  return commonApi('delete', `${CAMERA_PREFIX}/directory/${directory_id}`);
};

/**
 * 获取目录下的设备列表
 * @param directory_id 目录ID
 * @param params 查询参数
 */
export const getDirectoryDevices = (directory_id: number, params: {
  pageNo?: number;
  pageSize?: number;
  search?: string;
}) => {
  return commonApi('get', `${CAMERA_PREFIX}/directory/${directory_id}/devices`, params);
};

/**
 * 移动设备到目录
 * @param device_id 设备ID
 * @param directory_id 目录ID（0表示移动到根目录，即无目录）
 */
export const moveDeviceToDirectory = (device_id: string, directory_id: number | null) => {
  return commonApi('put', `${CAMERA_PREFIX}/device/${device_id}/directory`, {
    directory_id: directory_id === 0 ? null : directory_id
  });
};

// ====================== 流媒体管理工具函数 ======================
/**
 * 切换设备流媒体转发状态
 * @param device_id 设备ID
 * @param currentStatus 当前状态
 * @returns 操作结果
 */
export const toggleStreamForwarding = async (device_id: string, currentStatus: boolean) => {
  try {
    if (currentStatus) {
      return await stopStreamForwarding(device_id);
    } else {
      return await startStreamForwarding(device_id);
    }
  } catch (error) {
    throw new Error(`切换流媒体转发状态失败: ${error}`);
  }
};

/**
 * 检查所有设备的流媒体状态
 * @param deviceIds 设备ID数组
 * @returns 包含所有设备状态的Promise
 */
export const checkAllStreamStatus = async (deviceIds: string[]) => {
  const statusPromises = deviceIds.map(id => getStreamStatus(id));
  return Promise.all(statusPromises);
};

/**
 * 启动所有启用转发的设备
 * @param devices 设备列表
 * @returns 启动结果数组
 */
export const startAllEnabledDevices = async (devices: DeviceInfo[]) => {
  const enabledDevices = devices.filter(device => device.enable_forward);
  const startPromises = enabledDevices.map(device => startStreamForwarding(device.id));
  return Promise.all(startPromises);
};

/**
 * 停止所有设备的流媒体转发
 * @param deviceIds 设备ID数组
 * @returns 停止结果数组
 */
export const stopAllStreams = async (deviceIds: string[]) => {
  const stopPromises = deviceIds.map(id => stopStreamForwarding(id));
  return Promise.all(stopPromises);
};

// ====================== RTSP抓拍接口 ======================
/**
 * 从RTSP流抓取一帧图片
 * @param device_id 设备ID
 * @returns 包含图片ID和URL的响应
 */
export const captureSnapshot = (device_id: string) => {
  return commonApi('post', `${CAMERA_PREFIX}/device/${device_id}/snapshot`, {}, {}, false);
};

// ====================== 摄像头冲突检查接口 ======================
/**
 * 获取正在使用的摄像头ID列表（用于推流转发或算法任务）
 * @param task_type 任务类型：'stream_forward'（推流转发）或 'algorithm'（算法任务），不传则返回所有冲突的摄像头
 * @returns 包含冲突摄像头ID列表的响应
 */
export const getDeviceConflicts = (task_type?: 'stream_forward' | 'algorithm') => {
  return commonApi<{ code: number; msg: string; data: string[] }>(
    'get',
    `${CAMERA_PREFIX}/device/conflicts`,
    task_type ? { task_type } : {}
  );
};
