"""EDGE 本地配置：用户只写 NODE 地址，其余由 enroll 持久化。"""
from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any, Dict, Optional

EDGE_ROOT = Path(__file__).resolve().parent.parent
ENV_FILE = Path(os.environ.get('EDGE_ENV_FILE') or EDGE_ROOT / 'edge.env')
STATE_DIR = Path(os.environ.get('EDGE_STATE_DIR') or EDGE_ROOT / 'state')
STATE_FILE = STATE_DIR / 'edge.state.json'


def _parse_env_file(path: Path) -> Dict[str, str]:
    out: Dict[str, str] = {}
    if not path.is_file():
        return out
    for line in path.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('#') or '=' not in line:
            continue
        key, _, val = line.partition('=')
        out[key.strip()] = val.strip().strip('"').strip("'")
    return out


def load_env() -> Dict[str, str]:
    data = _parse_env_file(ENV_FILE)
    # 进程环境优先
    for key in (
        'EDGE_NODE_URL',
        'EDGE_JOIN_TOKEN',
        'EDGE_NODE_ID',
        'EDGE_AGENT_TOKEN',
    ):
        if os.environ.get(key):
            data[key] = os.environ[key].strip()
    return data


def save_env_value(key: str, value: str) -> None:
    ENV_FILE.parent.mkdir(parents=True, exist_ok=True)
    lines: list[str] = []
    found = False
    if ENV_FILE.is_file():
        for line in ENV_FILE.read_text(encoding='utf-8').splitlines():
            if line.startswith(f'{key}='):
                lines.append(f'{key}={value}')
                found = True
            else:
                lines.append(line)
    if not found:
        if lines and lines[-1].strip():
            lines.append('')
        lines.append(f'{key}={value}')
    ENV_FILE.write_text('\n'.join(lines) + '\n', encoding='utf-8')


def normalize_node_url(url: str) -> str:
    url = (url or '').strip().rstrip('/')
    if not url:
        raise ValueError('EDGE_NODE_URL 不能为空')
    if not url.startswith('http://') and not url.startswith('https://'):
        url = 'http://' + url
    return url.rstrip('/')


def admin_api_base(node_url: str) -> str:
    base = normalize_node_url(node_url)
    if base.endswith('/admin-api'):
        return base
    return f'{base}/admin-api'


def load_state() -> Dict[str, Any]:
    if not STATE_FILE.is_file():
        return {}
    try:
        return json.loads(STATE_FILE.read_text(encoding='utf-8'))
    except Exception:
        return {}


def save_state(state: Dict[str, Any]) -> None:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(json.dumps(state, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def merge_runtime_into_state(runtime: Dict[str, Any], node_id: Optional[int] = None, agent_token: Optional[str] = None) -> Dict[str, Any]:
    state = load_state()
    if node_id is not None:
        state['nodeId'] = node_id
    if agent_token:
        state['agentToken'] = agent_token
    state['runtimeConfig'] = runtime
    state['mqttBrokerUrls'] = runtime.get('mqttBrokerUrls') or state.get('mqttBrokerUrls')
    save_state(state)
    # 同步关键项到 edge.env，便于 systemd EnvironmentFile
    brokers = runtime.get('mqttBrokerUrls') or []
    if isinstance(brokers, list) and brokers:
        save_env_value('MQTT_BROKER_URLS', ','.join(str(x) for x in brokers))
    for env_key, rt_key in (
        ('MQTT_ALGO_TENANT', 'mqttAlgoTenant'),
        ('MQTT_ALGO_USERNAME', 'mqttUsername'),
        ('MQTT_ALGO_PASSWORD', 'mqttPassword'),
        ('MQTT_ALGO_CLIENT_ID', 'mqttClientId'),
        ('ALERT_IMAGES_DIR', 'alertImagesDir'),
        ('MEDIA_HOST_DATA_ROOT', 'mediaHostDataRoot'),
        ('MEDIA_SNAP_DIR', 'mediaSnapDir'),
    ):
        val = runtime.get(rt_key)
        if val is not None and str(val).strip():
            save_env_value(env_key, str(val))
    if node_id is not None:
        save_env_value('EDGE_NODE_ID', str(node_id))
    edge_node_id = runtime.get('edgeNodeId')
    if edge_node_id is not None:
        state['edgeNodeId'] = edge_node_id
        save_env_value('EDGE_EDGE_NODE_ID', str(edge_node_id))
    if agent_token:
        save_env_value('EDGE_AGENT_TOKEN', agent_token)
    return state
