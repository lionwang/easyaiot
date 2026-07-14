# EasyAIoT EDGE 模块

第八核心模块：**无限联邦边缘集群模式**——无界面、纯命令行边缘算法运行时。内存占用约 **512MB**，**Ceph 边缘 0 硬盘占用**，一行命令把普通开发板直接智能化，算力可铺开部署并汇聚上云；通过 MQTT/EMQX 无限扩容，算法任务从 VIDEO 控制面抽离，边缘侧 **不落本地业务盘、不直传 MinIO**。

## 你只需配置一项

```bash
# edge.env
EDGE_NODE_URL=http://<iot-node控制面主机>:48080
```

其余由控制面自动下发：

- EMQX / MQTT broker 列表（有序，支持故障从头探测）
- MQTT 租户 / 用户名 / 密码 / clientId
- Ceph 热缓冲路径（`ALERT_IMAGES_DIR` 等）
- 算法 Topic 约定、节点 ID / Agent Token

## 快速开始

```bash
cd EDGE
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

# 1. 写入 NODE 地址（唯一必配）
python -m edge config set-node http://10.0.0.10:48080

# 2. 向控制面登记本机并拉取运行时配置（自动）
python -m edge enroll

# 3. 前台运行（订阅 MQTT 任务指令，无 UI）
python -m edge run

# 常用命令
python -m edge status
python -m edge pull-config
python -m edge stop   # 优雅退出（若以服务方式运行见 docs）
```

生产环境建议在控制面配置 `easyaiot.edge.join-token`，边缘侧同步：

```bash
python -m edge config set-join-token <与控制面一致的令牌>
```

私网实验室可开启控制面 `easyaiot.edge.allow-open-enroll=true`，此时仅需 `EDGE_NODE_URL`。

## 设计原则

| 项 | 说明 |
|----|------|
| 无界面 | 全部 CLI / systemd，不提供 WEB |
| 全 MQTT | 启停/心跳/告警/后处理走算法总线（见 VIDEO 设计文档） |
| 不存储 | 业务图写 Ceph 共享路径；归档由中心 sink 完成 |
| 无限集群 | 多 EDGE 节点共享同一 EMQX 集群；任务由控制面调度下发 |
| 单配置入口 | 只配 NODE（iot-node）访问地址，动态领取其余 |

## 目录

```
EDGE/
  edge/           # CLI 与运行时（enroll / mqtt / workload）
  runtime/        # 算法执行包（自 VIDEO algorithm_* 抽离，演进中）
  docs/           # 模块设计
  edge.env.example
  requirements.txt
```

详细设计：[`docs/EDGE_MODULE_DESIGN.md`](docs/EDGE_MODULE_DESIGN.md)  
与总线对齐：[`VIDEO/docs/ALGORITHM_TASK_EMQX_DELIVERY_DESIGN.md`](../VIDEO/docs/ALGORITHM_TASK_EMQX_DELIVERY_DESIGN.md)
