#!/usr/bin/env python3
"""
全链路一键演示：边上报属性，边听下行；可选自动 HTTP 下发一次服务。

终端 1（推荐拆开跑 01 / 02 更直观）也能用本脚本同时验证上下行。

示例:
  python3 03_full_loop.py \\
    --product YOUR_PRODUCT --device YOUR_DEVICE --tenant-id 1 \\
    --password YOUR_PRODUCT_PASSWORD \\
    --invoke-api --token JWT --device-id 123
"""

from __future__ import annotations

import json
import math
import signal
import threading
import time
import urllib.error
import urllib.request
from typing import Any, Dict

from common import (
    build_arg_parser,
    build_message,
    connect_mqtt,
    device_all_filter,
    property_topic,
    publish_json,
    service_response_topic,
)


def extract_service_identifier(topic: str) -> str:
    parts = topic.strip("/").split("/")
    if len(parts) >= 7 and parts[3] == "service":
        return parts[6]
    return "unknown"


def http_invoke(api_base: str, token: str, device_id: int, service_id: str, params: Dict[str, Any]) -> None:
    url = f"{api_base.rstrip('/')}/device/{device_id}/invokeService?serviceIdentifier={service_id}"
    req = urllib.request.Request(
        url,
        data=json.dumps(params).encode("utf-8"),
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {token}",
            "X-Authorization": f"Bearer {token}",
        },
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        print(f"[API] {resp.status} {resp.read().decode('utf-8', errors='replace')}")


def main() -> None:
    parser = build_arg_parser("上下行同时跑，便于一次看完页面变化")
    parser.add_argument("--interval", type=float, default=3.0)
    parser.add_argument("--rounds", type=int, default=20, help="上行轮数后自动退出；0=不限")
    parser.add_argument("--invoke-api", action="store_true")
    parser.add_argument("--api-base", default="http://localhost:48080/admin-api")
    parser.add_argument("--token", default="")
    parser.add_argument("--device-id", type=int, default=0)
    parser.add_argument("--service-id", default="demo_switch")
    args = parser.parse_args()

    down_count = {"n": 0}

    def on_message(client, userdata, msg):
        text = msg.payload.decode("utf-8", errors="replace")
        # 忽略自己发的上行 echo
        if "/upstream/" in msg.topic:
            return
        down_count["n"] += 1
        print(f"\n[DOWN #{down_count['n']}] {msg.topic}\n{text}\n")
        if "/service/downstream/invoke/" in msg.topic:
            ident = extract_service_identifier(msg.topic)
            try:
                body = json.loads(text)
            except json.JSONDecodeError:
                body = {}
            reply = build_message(
                tenant_id=args.tenant_id,
                method="thing.service.invoke",
                params={"result": "ok"},
                request_id=body.get("requestId"),
                data={"success": True},
                code=0,
                msg="ok",
            )
            publish_json(
                client,
                service_response_topic(args.product, args.device, ident),
                reply,
                qos=args.qos,
            )

    client = connect_mqtt(args, on_message=on_message)
    client.subscribe(device_all_filter(args.product, args.device), qos=args.qos)
    print(f"[SUB] {device_all_filter(args.product, args.device)}")
    print("页面: 影子/运行状态看上行；在「服务→下发服务」看下行打到本终端")

    if args.invoke_api:
        if not args.token or not args.device_id:
            raise SystemExit("--invoke-api 需要 --token 与 --device-id")

        def _t():
            time.sleep(2)
            try:
                http_invoke(
                    args.api_base,
                    args.token,
                    args.device_id,
                    args.service_id,
                    {"action": "ping"},
                )
            except Exception as e:
                print(f"[API] {e}")

        threading.Thread(target=_t, daemon=True).start()

    stop = {"flag": False}

    def _stop(*_):
        stop["flag"] = True

    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)

    up_topic = property_topic(args.product, args.device)
    i = 0
    while not stop["flag"]:
        i += 1
        params = {
            "temperature": round(22 + 4 * math.sin(i / 2.5), 2),
            "humidity": round(55 + 8 * math.cos(i / 3.0), 2),
            "counter": i,
        }
        publish_json(
            client,
            up_topic,
            build_message(
                tenant_id=args.tenant_id,
                method="thing.property.post",
                params=params,
            ),
            qos=args.qos,
        )
        if args.rounds and i >= args.rounds:
            break
        time.sleep(args.interval)

    client.loop_stop()
    client.disconnect()
    print(f"[DONE] 上行 {i} 次，下行收到 {down_count['n']} 次")


if __name__ == "__main__":
    main()
