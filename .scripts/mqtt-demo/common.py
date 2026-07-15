#!/usr/bin/env python3
"""MQTT 联调公共配置与工具。"""

from __future__ import annotations

import argparse
import json
import time
import uuid
from typing import Any, Dict, Optional

try:
    import paho.mqtt.client as mqtt
except ImportError as e:  # pragma: no cover
    raise SystemExit(
        "缺少依赖: pip install paho-mqtt\n" + str(e)
    ) from e


def build_arg_parser(description: str) -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=description)
    p.add_argument("--host", default="localhost", help="EMQX 地址")
    p.add_argument("--port", type=int, default=1883, help="EMQX 端口")
    p.add_argument("--product", required=True, help="产品标识 productIdentification")
    p.add_argument("--device", required=True, help="设备标识 deviceIdentification")
    p.add_argument(
        "--tenant-id",
        type=int,
        required=True,
        help="租户 ID（payload 必填，与库里该设备 tenant_id 一致）",
    )
    p.add_argument(
        "--password",
        default="",
        help="MQTT 密码。设备鉴权模式为产品/设备密码；broker 模式可用 emqx 用户密码",
    )
    p.add_argument(
        "--auth-mode",
        choices=("device", "broker"),
        default="device",
        help="device=用户名 device&product；broker=任意 broker 账号（本地无 HTTP 鉴权时可用）",
    )
    p.add_argument(
        "--broker-user",
        default="emqx",
        help="auth-mode=broker 时的用户名",
    )
    p.add_argument(
        "--client-id",
        default="",
        help="MQTT ClientId，默认 demo-{device}",
    )
    p.add_argument("--qos", type=int, default=1, choices=(0, 1, 2))
    return p


def mqtt_username(args: argparse.Namespace) -> str:
    if args.auth_mode == "broker":
        return args.broker_user
    # 平台约定: {deviceIdentification}&{productIdentification}
    return f"{args.device}&{args.product}"


def mqtt_password(args: argparse.Namespace) -> str:
    if args.password:
        return args.password
    if args.auth_mode == "broker":
        return "123456"
    raise SystemExit("auth-mode=device 时必须通过 --password 传入产品/设备密码")


def mqtt_client_id(args: argparse.Namespace) -> str:
    return args.client_id or f"demo-{args.device}"


def property_topic(product: str, device: str) -> str:
    return f"/iot/{product}/{device}/property/upstream/report"


def event_topic(product: str, device: str, identifier: str = "alarm") -> str:
    return f"/iot/{product}/{device}/event/upstream/report/{identifier}"


def log_topic(product: str, device: str) -> str:
    return f"/iot/{product}/{device}/log/upstream/report"


def service_down_filter(product: str, device: str) -> str:
    return f"/iot/{product}/{device}/service/downstream/#"


def property_down_filter(product: str, device: str) -> str:
    return f"/iot/{product}/{device}/property/downstream/#"


def all_down_filter(product: str, device: str) -> str:
    return f"/iot/{product}/{device}/+"


def device_all_filter(product: str, device: str) -> str:
    return f"/iot/{product}/{device}/#"


def service_response_topic(product: str, device: str, identifier: str) -> str:
    return (
        f"/iot/{product}/{device}/service/upstream/invoke/{identifier}/response"
    )


def build_message(
    *,
    tenant_id: int,
    method: str,
    params: Any,
    request_id: Optional[str] = None,
    data: Any = None,
    code: Optional[int] = None,
    msg: Optional[str] = None,
) -> Dict[str, Any]:
    body: Dict[str, Any] = {
        "tenantId": tenant_id,
        "requestId": request_id or str(uuid.uuid4()).replace("-", "")[:16],
        "method": method,
        "params": params,
    }
    if data is not None:
        body["data"] = data
    if code is not None:
        body["code"] = code
    if msg is not None:
        body["msg"] = msg
    return body


def dumps(payload: Dict[str, Any]) -> str:
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def connect_mqtt(args: argparse.Namespace, on_message=None) -> mqtt.Client:
    client_id = mqtt_client_id(args)
    # paho-mqtt 2.x / 1.x 兼容
    try:
        client = mqtt.Client(
            mqtt.CallbackAPIVersion.VERSION1,  # type: ignore[attr-defined]
            client_id=client_id,
            clean_session=True,
        )
    except Exception:
        client = mqtt.Client(client_id=client_id, clean_session=True)

    user = mqtt_username(args)
    pwd = mqtt_password(args)
    client.username_pw_set(user, pwd)

    connected = {"ok": False}

    def on_connect(c, userdata, flags, rc, properties=None):
        if rc == 0:
            connected["ok"] = True
            print(f"[MQTT] 已连接 {args.host}:{args.port} clientId={client_id} user={user}")
        else:
            print(f"[MQTT] 连接失败 rc={rc}（检查 EMQX、鉴权账号、HTTP Auth 是否指向 sink）")

    client.on_connect = on_connect
    if on_message is not None:
        client.on_message = on_message

    client.connect(args.host, args.port, keepalive=60)
    client.loop_start()

    for _ in range(50):
        if connected["ok"]:
            break
        time.sleep(0.1)
    if not connected["ok"]:
        client.loop_stop()
        raise SystemExit("MQTT 连接超时，请确认 Broker 可达且账号正确")
    return client


def publish_json(client: mqtt.Client, topic: str, payload: Dict[str, Any], qos: int = 1) -> None:
    raw = dumps(payload)
    info = client.publish(topic, raw, qos=qos)
    info.wait_for_publish(timeout=5)
    print(f"[PUB] {topic}\n      {raw}")
