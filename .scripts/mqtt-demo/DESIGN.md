# MQTT 上下行联调演示 — 详细设计文档

> 目录：`.scripts/mqtt-demo/`  
> 目的：用可运行的 Python 脚本模拟真实设备，验证 EasyAIoT「设备 ↔ EMQX ↔ iot-sink ↔ Kafka ↔ PG/TDengine ↔ Web」链路已打通，并在管理端页面看到可观察的数据变化。

---

## 1. 背景与目标

### 1.1 背景

平台数据采集默认走 **EMQX 外部 Broker**，而不是 Web 直连 MQTT：

- **上行**：设备 Publish → EMQX → `iot-sink` 订阅 `/iot/#` → 解码 → Kafka → 存储（PG 影子/在线状态 + TDengine 时序）→ Web 查询展示  
- **下行**：Web/API 发令 → `iot-device` → `IotDownstreamMessageApi` → Kafka 网关 Topic → `iot-sink` EMQX 下行订阅器 → Publish 到设备 Topic → 设备 Subscribe 收到

群友反馈曾出现：消息总线循环依赖、订阅未注册、TDengine 入库/建表、无脚本配置页等问题；已在工程侧对齐参考实现。本目录脚本用于**端到端验收**，避免只看代码无法证伪。

### 1.2 设计目标

| 编号 | 目标 | 成功标准 |
|------|------|----------|
| G1 | 证明上行打通 | 脚本周期上报后，设备影子 / 运行状态 / 历史出现变化，设备 ONLINE |
| G2 | 证明下行打通 | Web「下发服务」或 HTTP `invokeService` 后，脚本终端打印下行 Topic/Payload |
| G3 | 证明下行闭环（可选） | 设备自动上行 service response，链路完整 |
| G4 | 可复现、可配置 | 全部关键参数走 CLI，不写死环境 |

### 1.3 非目标

- 不替代产线设备 SDK / 合规认证压测  
- 不覆盖 Alink `/alink/...` 专用 Topic（本演示固定走标准 `/iot/...` + Topic Codec）  
- 不强制配置协议 JS 脚本（标准 JSON **可不配脚本**；`rawDataToProtocol` 仅私有协议需要）

---

## 2. 总体架构

```text
┌─────────────┐  MQTT Publish (/iot/.../property/upstream/report)
│ Python 模拟  │ ──────────────────────────────────────────────┐
│ 设备脚本     │ ◄─────────────────────────────────────────────┤
└─────────────┘  MQTT Subscribe (/iot/.../service/downstream/#) │
                                                               ▼
                                                         ┌──────────┐
                                                         │  EMQX    │
                                                         │ :1883    │
                                                         └────┬─────┘
                                sink 客户端订阅 /iot/#          │
                                sink 客户端下行 Publish          │
                                                               ▼
┌──────────┐   HTTP/Feign    ┌────────────┐   Kafka    ┌────────────┐
│ Web 管理端 │ ─────────────► │ iot-device  │ ─────────► │  iot-sink  │
│ 影子/服务  │ ◄───────────── │ 命令/查询   │ ◄───────── │ 编解码/入库 │
└──────────┘                 └────────────┘            └─────┬──────┘
       ▲                                                     │
       │              查询 API                               │
       └────────────── iot-tdengine / PG ◄───────────────────┘
                      (st_property_upstream_report / shadow)
```

### 2.1 关键进程依赖

| 组件 | 作用 | 本地关键配置参考 |
|------|------|------------------|
| EMQX | MQTT Broker | `1883`；可选 HTTP Auth → sink `:8090` |
| Kafka | 设备消息总线 | sink `IotKafkaMessageBus` |
| iot-sink | 上下行协议网关 + 入库 | `iot.gateway.protocol.emqx.enabled=true` |
| iot-device | 元数据、下发命令、影子查询 | `/deviceCommand`、`/device/{id}/invokeService` |
| iot-tdengine | 历史/运行状态查询 | 库 `iot_device`，表 `st_property_upstream_report_*` |
| iot-gateway | 管理端 API 入口 | 如 `48080`，前缀 `/admin-api` |
| PostgreSQL | 设备、影子、日志 | device 表 extension/shadow |
| Web | 人工观察 | 设备详情各 Tab |

---

## 3. 协议设计

### 3.1 Topic 规范（标准 `/iot`）

统一前缀：

```text
/iot/{productIdentification}/{deviceIdentification}/...
```

本演示用到的 Topic：

| 方向 | Topic | Method | 说明 |
|------|-------|--------|------|
| 上行 | `/iot/{p}/{d}/property/upstream/report` | `thing.property.post` | 属性上报（主验证） |
| 上行 | `/iot/{p}/{d}/event/upstream/report/{identifier}` | `thing.event.post` | 可选事件 |
| 上行 | `/iot/{d path}/log/upstream/report` | `thing.log.post` | 可选日志 |
| 下行 | `/iot/{p}/{d}/service/downstream/invoke/{identifier}` | `thing.service.invoke` | 云端调服务 |
| 上行回执 | `/iot/{p}/{d}/service/upstream/invoke/{identifier}/response` | `thing.service.invoke` | 设备应答 |
| 下行 | `/iot/{p}/{d}/property/downstream/desired/set` | `thing.property.set` | 属性期望设置（页面/API 可能发） |

> 源码枚举：`DEVICE/iot-sink/.../IotDeviceTopicEnum.java`  
> 下行 Topic 组装：`IotMqttTopicUtils` / `DeviceServiceImpl.invokeService`

### 3.2 Payload（Topic Codec / JSON）

`IotTopicDeviceMessageCodec` 对 `/iot/**` 直接 JSON ↔ `IotDeviceMessage`。

**最低必填字段：**

```json
{
  "tenantId": 1,
  "requestId": "a1b2c3d4e5f60718",
  "method": "thing.property.post",
  "params": {
    "temperature": 23.5,
    "humidity": 56.2,
    "counter": 1
  }
}
```

约束说明：

1. **`tenantId` 必填**：`IotEmqxUpstreamHandler` 在解码前校验，缺失直接丢弃。  
2. **`method` 建议带**：与 Topic 标准映射一致；存储层也会按 Topic 标准化 method。  
3. **`params`**：属性上报的业务数据；影子与 TDengine `params` 列写入该对象的 JSON。  
4. 无需 `version: 1.0`（那是 Alink Codec 要求；本演示不走 `/alink/`）。

### 3.3 MQTT 连接鉴权

| 模式 | CLI | Username | Password | 适用场景 |
|------|-----|----------|----------|----------|
| `device`（默认） | `--auth-mode device` | `{deviceIdentification}&{productIdentification}` | 产品/设备密码 | EMQX 已配 HTTP Auth → sink `/mqtt/auth` |
| `broker` | `--auth-mode broker` | 默认 `emqx`（`--broker-user`） | 默认 `123456` | 本地未配设备 HTTP 鉴权，仅验证 Publish/Subscribe 通路 |

解析约定（sink）：`IotDeviceAuthUtils.parseUsername` →  
`usernameParts[0]=deviceIdentification`，`usernameParts[1]=productIdentification`。

**ClientId**：默认 `demo-{device}`，可用 `--client-id` 覆盖。注意与库中设备 `clientId`/鉴权策略是否冲突。

> 说明：即使设备用 `broker` 模式入连，只要消息发到 `/iot/...` 且 sink 已订阅，**上行业务仍可被 sink 消费**。`device` 模式才是接近生产的设备认证。

### 3.4 编解码与脚本

处理顺序（`IotDeviceMessageServiceImpl.decodeDeviceMessageByTopic`）：

1. 从 Topic 解析 `productIdentification`  
2. 若有产品启用 JS 脚本 → `rawDataToProtocol`  
3. 否则 **原样交给 Codec**（标准 JSON 可通）  
4. Topic Codec 反序列化为 `IotDeviceMessage`

**结论**：联调演示不需要配置「协议脚本」；页面产品抽屉「协议脚本」仅私有协议使用。

---

## 4. 数据落库与页面映射

### 4.1 上行属性路径

```text
Publish property/upstream/report
  → sink decode + Kafka iot_device_message
  → UpstreamSubscriber / Handler
  → DeviceDataStorageService
       ├─ PG：设备 ONLINE；属性上报同步写 shadow（extension）
       └─ TD：INSERT INTO iot_device.st_property_upstream_report_{safeDeviceId}
              USING st_property_upstream_report TAGS(...)
```

子表命名规则（与查询侧一致）：

- 基础名：`st_property_upstream_report_{deviceIdentification}`  
- 非法字符替换为 `_`；若首字符非字母/`_`，前缀 `d_`  

### 4.2 Web 应对应看到的变化

| 页面 Tab | 数据来源 | 演示预期 |
|----------|----------|----------|
| 影子 Shadow | PG `device.extension.shadow` | `temperature/humidity/counter` 随上报刷新 |
| 运行状态 | TDengine 最近一条 `params` JSON 拆物模型属性 | 属性 code 对齐时显示数值 |
| 历史 | `st_property_upstream_report_*` | 多条 report_time 记录 |
| 事件 | 事件 Topic + 存储 | `--with-event` 时增加 |
| 日志 | 日志 Topic / append log | `--with-log` 时增加 |
| 服务 | 下行命令 + 可选 response | `02` 脚本打印下行；回执可闭环 |
| 设备列表在线状态 | PG connect status | ONLINE |

> 物模型建议至少有 `temperature`、`humidity`（或改脚本 `params` key 对齐已有属性 code）。**即使未对齐物模型，影子 JSON 仍应变化**，可作为上行成功的第一证据。

### 4.3 下行路径

```text
Web「下发服务」或 POST /device/{deviceId}/invokeService
  → DeviceServiceImpl 组装 IotDeviceMessage
       method=thing.service.invoke
       topic=/iot/{p}/{d}/service/downstream/invoke/{serviceIdentifier}
       tenantId / deviceId(数字主键)
  → IotDownstreamMessageApi → Kafka 网关 Topic
  → IotEmqxDownstreamSubscriber（延迟 register）
  → IotEmqxDownstreamHandler → EMQX Publish
  → 02 脚本 on_message 打印
  → （可选）自动 Publish .../service/upstream/invoke/{id}/response
```

---

## 5. 目录与模块设计

```text
.scripts/mqtt-demo/
├── DESIGN.md                 # 本文档
├── env.example               # 参数备忘（非强制加载）
├── common.py                 # 连接、Topic、Payload、发布工具
├── 01_uplink_property.py     # 上行属性演示
├── 02_downlink_listen.py     # 下行监听 + 可选 API 触发 + 自动回执
└── 03_full_loop.py           # 上下行同进程联跑（可选）
```

### 5.1 `common.py`

职责：

- 统一 CLI：`--host/--port/--product/--device/--tenant-id/--password/--auth-mode/...`  
- MQTT 连接（兼容 paho-mqtt 1.x / 2.x Callback API）  
- Topic 拼装与标准 Payload 构造（强制 `tenantId`）  
- `publish_json` 同步等待 publish 完成，终端打印便于对照日志  

### 5.2 `01_uplink_property.py`

- 按 `--interval`（默认 3s）上报正弦变化的温湿度 + 自增 `counter`  
- 可选 `--with-event` / `--with-log`  
- `--rounds 0` 表示常驻，直到 Ctrl+C  

**验收**：盯 Web 影子与 ONLINE，不依赖下行。

### 5.3 `02_downlink_listen.py`

- 订阅 `/iot/{p}/{d}/#` 等下行相关 Topic  
- 默认 `--auto-reply`：收到 service invoke 后回 response  
- `--invoke-api`：用 JWT 调网关 `invokeService`，无需手点页面  

**验收**：终端出现 `[DOWN #n]`；Web 侧命令已发出。

### 5.4 `03_full_loop.py`

- 同进程既 Publish 属性又 Subscribe 下行  
- 适合单窗口快速冒烟；细查问题仍建议拆开跑 01/02  

---

## 6. 环境准备与运行手册

### 6.1 依赖

```bash
pip install paho-mqtt
cd /projects/new/easyaiot/.scripts/mqtt-demo
```

### 6.2 服务 checklist

- [ ] EMQX `1883` 可达  
- [ ] Kafka 可用，sink 消息总线为 kafka  
- [ ] `iot-sink` 已启动且 `emqx.enabled=true`，订阅含 `/iot/#`  
- [ ] `iot-device` / `iot-tdengine` / `iot-gateway` / Web 可用  
- [ ] TDengine 超级表已存在（sink 启动 `TdSuperTableInitializer` 或执行 `.scripts/tdengine/tdengine_super_tables.sql`）  
- [ ] 库中存在目标产品与设备，`tenant_id`、标识与参数一致，设备状态 ENABLE  

若曾用错误 schema 建过超级表：`CREATE IF NOT EXISTS` **不会改结构**，需手工 DROP 后再启 sink。

### 6.3 参数收集表

| 参数 | 来源 | 示例 |
|------|------|------|
| productIdentification | 产品管理 | `demo_product` |
| deviceIdentification | 设备详情 | `demo_device_001` |
| tenantId | 设备/租户 | `1` |
| password | 产品凭据（device 模式） | `******` |
| device 主键 ID | 设备详情 URL / 库 `id` | `10001` |
| JWT | 浏览器登录后 token | `eyJ...` |
| api-base | 网关 | `http://localhost:48080/admin-api` |

### 6.4 推荐操作序列（验收上下行）

**步骤 A — 上行**

```bash
python3 01_uplink_property.py \
  --product demo_product \
  --device demo_device_001 \
  --tenant-id 1 \
  --password '产品密码'
```

本地无设备 HTTP Auth 时：

```bash
python3 01_uplink_property.py \
  --product demo_product --device demo_device_001 --tenant-id 1 \
  --auth-mode broker --password 123456
```

打开 Web → 设备详情 → **影子**：确认 JSON 在变。

**步骤 B — 下行**

另开终端：

```bash
python3 02_downlink_listen.py \
  --product demo_product \
  --device demo_device_001 \
  --tenant-id 1 \
  --password '产品密码'
```

Web → **服务 → 下发服务**。终端应打印：

```text
[DOWN #1] topic=/iot/demo_product/demo_device_001/service/downstream/invoke/xxx
payload=...
[ACK] 已回执服务 xxx
```

**步骤 C — API 下行（可选）**

```bash
python3 02_downlink_listen.py \
  --product demo_product --device demo_device_001 --tenant-id 1 \
  --password '产品密码' \
  --invoke-api \
  --api-base http://localhost:48080/admin-api \
  --token 'JWT' \
  --device-id 10001 \
  --service-id demo_switch
```

---

## 7. 观测与排障

### 7.1 日志关键词

| 阶段 | 服务 | 关键词 |
|------|------|--------|
| 收到 MQTT | sink | `IotEmqxUpstreamHandler` / `收到 MQTT 消息` |
| 缺 tenantId | sink | `message is missing tenantId` |
| 总线注册 | sink | `上行消息订阅成功` / `EMQX 下行订阅器注册成功` |
| TD 写入 | sink | `TDEngine数据插入成功` / `超级表初始化` |
| 下行发布 | sink | `IotEmqxDownstreamHandler` |
| 命令组装 | device | `invokeService` / `服务调用消息发送成功` |

### 7.2 常见失败矩阵

| 现象 | 可能原因 | 处理 |
|------|----------|------|
| MQTT 连接失败 | Broker 不通 / 账号错 | 检查 1883；先试 `--auth-mode broker` |
| 上行无页面变化 | payload 无 `tenantId` | 必传 `--tenant-id` |
| 上行无变化但 MQTT 成功 | sink 未订 `/iot/#` 或 Kafka 挂 | 查 sink `application-*.yaml` 与 Kafka |
| 影子不变、TD 有数 | storeToDevice 条件/deviceId | 确认 device 数字主键与消息关联成功 |
| TD 插入失败 | 超级表不存在/schema 不一致 | 跑 DDL 或重启 initializer；必要时 DROP 旧表 |
| 运行状态空 | 物模型 code 与 params key 不一致 | 对齐属性 code，或先看影子 |
| 下行页面点了脚本无打印 | 设备未订阅 / 下行 Kafka 或 DownstreamSubscriber 未注册 | 确认 02 在跑；查 sink 启动日志 register |
| invokeService 401 | token 无效 | 重新登录取 JWT |
| invokeService 成功但无 MQTT | `IotDownstreamMessageApi` 未注入 / method-topic 错 | 查 device 与 sink 服务发现、Feign |

### 7.3 验收记录模板（建议手填）

```text
日期:
环境: local / dev
产品/设备/租户:
上行:  [ ] MQTT 成功  [ ] 影子变化  [ ] ONLINE  [ ] 历史有点
下行:  [ ] 终端收到 DOWN  [ ] Topic 含 service/downstream/invoke
回执:  [ ] ACK 已发送（可选）
备注:
```

---

## 8. 安全与注意项

1. **不要把真实 JWT/密码提交进仓库**；`env.example` 仅作占位。  
2. `broker` 模式仅用于开发联调，生产必须走设备 HTTP Auth + ACL。  
3. Payload 禁止伪造其它租户 `tenantId` 做越权测试于共享环境。  
4. QoS 默认 1，与 sink 本地配置一致；压测时可调，但不改变业务语义。  
5. 脚本 ClientId 与真实设备同时在线可能导致互踢（视 EMQX 策略而定）。

---

## 9. 与平台代码的对应关系（便于二次维护）

| 能力 | 代码位置 |
|------|----------|
| 上行 MQTT 入口 | `IotEmqxUpstreamHandler` |
| 下行 MQTT 出口 | `IotEmqxDownstreamHandler` |
| 下行总线订阅（防循环依赖） | `IotEmqxDownstreamSubscriber` + `SmartInitializingSingleton` |
| 上行总线订阅 | `IotUpstreamMessageSubscriber` |
| Topic / Method | `IotDeviceTopicEnum` / `IotDeviceMessageMethodEnum` |
| 存储 | `DeviceDataStorageService` |
| TD 超级表初始化 | `TdSuperTableInitializer` |
| 服务下发 API | `DeviceController.invokeService` / `DeviceCommandController` |
| 网关路由 | `iot-gateway` → `/admin-api/device/**`、`/admin-api/sink/**`、`/admin-api/tdengine/**` |
| 前端服务下发 | `WEB/.../devices/components/Service/index.vue` |
| 产品脚本配置 | `WEB/.../product/components/ProductScript.vue` + sink `ProductScriptController` |

---

## 10. 演进建议

1. 增加 `04_property_set_ack.py`：专门验证属性期望设置下行与 ACK。  
2. 从 Web 登录接口自动取 token，减少手工拷贝 JWT。  
3. 增加对 TDengine REST 的只读校验脚本，做到「无页面也能断言入库」。  
4. 接入 CI：对 local compose 做 smoke（需测试账号与隔离租户）。

---

## 附录 A — Payload 示例

**属性上报**

```json
{
  "tenantId": 1,
  "requestId": "demo000000000001",
  "method": "thing.property.post",
  "params": {
    "temperature": 24.1,
    "humidity": 51.0,
    "counter": 7,
    "demoSource": "mqtt-demo/01_uplink_property.py"
  }
}
```

**服务回执**

```json
{
  "tenantId": 1,
  "requestId": "来自下行的requestId",
  "method": "thing.service.invoke",
  "params": { "result": "ok" },
  "data": { "success": true },
  "code": 0,
  "msg": "demo ok"
}
```

## 附录 B — 一键冒烟（可选）

```bash
# 终端 1：上下行同进程
python3 03_full_loop.py \
  --product demo_product --device demo_device_001 --tenant-id 1 \
  --password '产品密码' --rounds 10

# 另开浏览器观察影子；并在服务页点一次下发，看终端 [DOWN]
```

---

**文档版本**：1.0  
**适用范围**：EasyAIoT 当前 `/iot` + EMQX + sink 消息总线架构  
**维护**：随 Topic/鉴权/入库策略变更时同步更新第 3、4、9 节
