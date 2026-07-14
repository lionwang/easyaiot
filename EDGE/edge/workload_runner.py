"""本节点工作负载：按 MQTT cmd 拉起/停止算法进程（runtime 目录）。"""
from __future__ import annotations

import logging
import os
import signal
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, Optional

from edge.config import EDGE_ROOT

logger = logging.getLogger('edge.workload')

RUNTIME_ROOT = Path(os.environ.get('EDGE_RUNTIME_ROOT') or EDGE_ROOT / 'runtime')

_SERVICES = {
    'realtime': 'realtime_algorithm_service',
    'snap': 'snapshot_algorithm_service',
    'patrol': 'patrol_algorithm_service',
}

_procs: Dict[int, subprocess.Popen] = {}


def _deploy_script(task_type: str) -> Path:
    name = _SERVICES.get(task_type or 'realtime', 'realtime_algorithm_service')
    script = RUNTIME_ROOT / 'services' / name / 'run_deploy.py'
    return script


def start_task(cmd_payload: Dict[str, Any], runtime_env: Dict[str, str]) -> Dict[str, Any]:
    task_id = int(cmd_payload.get('taskId') or 0)
    if not task_id:
        raise ValueError('cmd 缺少 taskId')
    if task_id in _procs and _procs[task_id].poll() is None:
        return {'success': True, 'processId': _procs[task_id].pid, 'reason': 'already_running'}

    task_type = (cmd_payload.get('taskType') or 'realtime').strip()
    deploy = cmd_payload.get('deploy') or {}
    script = _deploy_script(task_type)
    if not script.is_file():
        # 兼容：cmd 自带 command/workDir
        command = deploy.get('command')
        work_dir = deploy.get('workDir')
        if command and work_dir:
            return _spawn(task_id, list(command), work_dir, runtime_env, deploy.get('env') or {})
        raise FileNotFoundError(
            f'未找到算法入口 {script}，请同步 VIDEO 算法包到 EDGE/runtime 或在 cmd.deploy 中提供 command'
        )

    python_exec = sys.executable
    command = [python_exec, str(script)]
    work_dir = str(script.parent)
    return _spawn(task_id, command, work_dir, runtime_env, deploy.get('env') or {})


def _spawn(
    task_id: int,
    command: list,
    work_dir: str,
    runtime_env: Dict[str, str],
    deploy_env: Dict[str, Any],
) -> Dict[str, Any]:
    env = os.environ.copy()
    env.update({k: str(v) for k, v in runtime_env.items() if v is not None})
    env.update({k: str(v) for k, v in deploy_env.items() if v is not None})
    env.setdefault('TASK_ID', str(task_id))
    # 边缘不存储：强制 Ceph 路径语义；不做 MinIO 同步上传
    env.setdefault('ALGO_MEDIA_REF_MODE', 'shared_fs')
    env.setdefault('ALGO_UPLOAD_MINIO_SYNC', 'false')
    env.setdefault('ALGO_BUS_TRANSPORT', 'mqtt')

    log_dir = Path(work_dir) / 'logs' / f'task_{task_id}'
    log_dir.mkdir(parents=True, exist_ok=True)
    stdout = open(log_dir / 'edge_stdout.log', 'a', encoding='utf-8')
    proc = subprocess.Popen(
        command,
        cwd=work_dir,
        env=env,
        stdout=stdout,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    _procs[task_id] = proc
    logger.info('started task_id=%s pid=%s cmd=%s', task_id, proc.pid, command)
    return {'success': True, 'processId': proc.pid, 'reason': None}


def stop_task(task_id: int) -> Dict[str, Any]:
    proc = _procs.get(task_id)
    if not proc or proc.poll() is not None:
        _procs.pop(task_id, None)
        return {'success': True, 'processId': None, 'reason': 'not_running'}
    try:
        os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
    except Exception:
        proc.terminate()
    try:
        proc.wait(timeout=15)
    except Exception:
        try:
            os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
        except Exception:
            proc.kill()
    _procs.pop(task_id, None)
    logger.info('stopped task_id=%s', task_id)
    return {'success': True, 'processId': None, 'reason': None}


def restart_task(cmd_payload: Dict[str, Any], runtime_env: Dict[str, str]) -> Dict[str, Any]:
    task_id = int(cmd_payload.get('taskId') or 0)
    stop_task(task_id)
    return start_task(cmd_payload, runtime_env)
