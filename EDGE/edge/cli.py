"""EDGE 纯命令行入口。"""
from __future__ import annotations

import argparse
import json
import logging
import signal
import sys

from edge import __version__
from edge.config import (
    load_env,
    load_state,
    merge_runtime_into_state,
    normalize_node_url,
    save_env_value,
)
from edge import node_client

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
)
logger = logging.getLogger('edge')


def cmd_config(args: argparse.Namespace) -> int:
    if args.action == 'set-node':
        url = normalize_node_url(args.value)
        save_env_value('EDGE_NODE_URL', url)
        print(f'已写入 EDGE_NODE_URL={url}')
        return 0
    if args.action == 'set-join-token':
        save_env_value('EDGE_JOIN_TOKEN', args.value)
        print('已写入 EDGE_JOIN_TOKEN')
        return 0
    if args.action == 'show':
        env = load_env()
        state = load_state()
        safe = {k: ('***' if 'TOKEN' in k or 'PASSWORD' in k else v) for k, v in env.items()}
        print(json.dumps({'env': safe, 'stateKeys': list(state.keys()), 'nodeId': state.get('nodeId')}, ensure_ascii=False, indent=2))
        return 0
    print('未知 config 动作', file=sys.stderr)
    return 2


def cmd_enroll(args: argparse.Namespace) -> int:
    data = node_client.enroll(
        node_role=args.role,
        max_task_count=args.max_tasks,
        join_token=args.join_token,
    )
    node_id = data.get('nodeId')
    token = data.get('agentToken')
    runtime = data.get('runtimeConfig') or {}
    merge_runtime_into_state(runtime, node_id=int(node_id), agent_token=token)
    print(json.dumps({
        'nodeId': node_id,
        'mqttBrokerUrls': runtime.get('mqttBrokerUrls'),
        'alertImagesDir': runtime.get('alertImagesDir'),
        'mqttUsername': runtime.get('mqttUsername'),
    }, ensure_ascii=False, indent=2))
    print('enroll 成功：运行时配置已写入 state/ 与 edge.env')
    return 0


def cmd_pull_config(_: argparse.Namespace) -> int:
    runtime = node_client.pull_runtime_config()
    state = load_state()
    merge_runtime_into_state(
        runtime,
        node_id=int(state.get('nodeId') or load_env().get('EDGE_NODE_ID') or 0) or None,
        agent_token=state.get('agentToken') or load_env().get('EDGE_AGENT_TOKEN'),
    )
    print(json.dumps({
        'mqttBrokerUrls': runtime.get('mqttBrokerUrls'),
        'mediaHostDataRoot': runtime.get('mediaHostDataRoot'),
        'mqttClientId': runtime.get('mqttClientId'),
    }, ensure_ascii=False, indent=2))
    return 0


def cmd_status(_: argparse.Namespace) -> int:
    env = load_env()
    state = load_state()
    rt = state.get('runtimeConfig') or {}
    print(json.dumps({
        'version': __version__,
        'nodeUrl': env.get('EDGE_NODE_URL'),
        'nodeId': state.get('nodeId') or env.get('EDGE_NODE_ID'),
        'mqttBrokerUrls': rt.get('mqttBrokerUrls') or env.get('MQTT_BROKER_URLS'),
        'enrolled': bool(state.get('nodeId') and state.get('agentToken')),
    }, ensure_ascii=False, indent=2))
    return 0


def cmd_run(args: argparse.Namespace) -> int:
    env = load_env()
    if not env.get('EDGE_NODE_URL'):
        print('请先: python -m edge config set-node <NODE地址>', file=sys.stderr)
        return 2
    state = load_state()
    if not state.get('nodeId') or not state.get('agentToken'):
        logger.info('尚未 enroll，自动执行 enroll…')
        cmd_enroll(argparse.Namespace(role=args.role, max_tasks=args.max_tasks, join_token=None))
        state = load_state()
    else:
        # 每次启动刷新动态配置（MQTT 列表可能变更）
        try:
            cmd_pull_config(argparse.Namespace())
            state = load_state()
        except Exception as exc:
            logger.warning('pull-config 失败，使用本地缓存: %s', exc)

    runtime = state.get('runtimeConfig') or {}
    node_id = int(state['nodeId'])
    from edge.mqtt_runtime import EdgeMqttRuntime  # 延迟导入，避免 status 等命令强依赖 paho
    rt = EdgeMqttRuntime(runtime, node_id)

    def _stop(*_):
        logger.info('收到退出信号')
        rt.stop()

    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)
    print(f'EDGE 运行中 nodeId={node_id} brokers={runtime.get("mqttBrokerUrls")} （Ctrl+C 退出）')
    rt.run_forever()
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog='edge', description='EasyAIoT EDGE — 无界面边缘算法运行时')
    p.add_argument('--version', action='version', version=f'edge {__version__}')
    sub = p.add_subparsers(dest='command', required=True)

    c = sub.add_parser('config', help='本地配置（只需 set-node）')
    c.add_argument('action', choices=['set-node', 'set-join-token', 'show'])
    c.add_argument('value', nargs='?', default='')
    c.set_defaults(func=cmd_config)

    e = sub.add_parser('enroll', help='向 NODE 登记并领取 MQTT/路径等全部动态配置')
    e.add_argument('--role', default='compute', help='节点角色，默认 compute')
    e.add_argument('--max-tasks', type=int, default=1)
    e.add_argument('--join-token', default=None)
    e.set_defaults(func=cmd_enroll)

    sub.add_parser('pull-config', help='刷新运行时配置').set_defaults(func=cmd_pull_config)
    sub.add_parser('status', help='查看本地状态').set_defaults(func=cmd_status)

    r = sub.add_parser('run', help='连接 MQTT 并接收算法任务指令')
    r.add_argument('--role', default='compute')
    r.add_argument('--max-tasks', type=int, default=1)
    r.set_defaults(func=cmd_run)

    return p


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.command == 'config' and args.action != 'show' and not args.value:
        parser.error(f'config {args.action} 需要 value')
    return int(args.func(args))
