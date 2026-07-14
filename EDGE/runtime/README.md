# EDGE/runtime — 算法执行包

此处承接从 `VIDEO/services/{realtime,snapshot,patrol}_algorithm_service` 抽离的边缘推理运行时。

## 当前策略

首期可用同步脚本从 VIDEO 最小包对齐（或软链），避免阻塞 EDGE 纳管/MQTT 主链路落地：

```bash
# 示例：将 VIDEO 算法最小集同步到本目录（在仓库根执行）
rsync -a --relative \
  VIDEO/./services/realtime_algorithm_service \
  VIDEO/./services/snapshot_algorithm_service \
  VIDEO/./services/patrol_algorithm_service \
  EDGE/runtime/
```

控制面 `task.cmd.deploy` 也可直接携带 `command` + `workDir`（远程已同步的 VIDEO 路径），EDGE `workload_runner` 会优先使用。

## 约束

- `ALGO_MEDIA_REF_MODE=shared_fs`：图写 Ceph，不 MinIO 同步上传  
- `ALGO_BUS_TRANSPORT=mqtt`：事件走 EMQX  
- 无 Flask 管理面、无本地业务库职责  
