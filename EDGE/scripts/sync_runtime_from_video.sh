#!/usr/bin/env bash
# 将 VIDEO 算法最小运行集同步到 EDGE/runtime（演进期过渡）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEST="${ROOT}/EDGE/runtime"
mkdir -p "${DEST}/services"
for svc in realtime_algorithm_service snapshot_algorithm_service patrol_algorithm_service; do
  src="${ROOT}/VIDEO/services/${svc}"
  if [[ -d "${src}" ]]; then
    rsync -a --delete "${src}/" "${DEST}/services/${svc}/"
    echo "synced ${svc}"
  else
    echo "skip missing ${src}" >&2
  fi
done
echo "done → ${DEST}"
