#!/usr/bin/env python3
"""
上行演示：模拟设备周期性上报属性。

运行后到 Web 打开该设备详情，刷新查看：
  - 影子 Shadow：params 会更新
  - 运行状态 / 历史：st_property_upstream_report 有新数据
  - 设备列表：应变为 ONLINE

示例:
  python3 01_uplink_property.py \\
    --product YOUR_PRODUCT --device YOUR_DEVICE --tenant-id 1 \\
    --password YOUR_PRODUCT_PASSWORD

若本地 EMQX 还没配 HTTP 鉴权，可先:
  python3 01_uplink_property.py ... --auth-mode broker --password 123456
"""

from __future__ import annotations

import math
import signal
import sys
import time

from common import (
    build_arg_parser,
    build_message,
    connect_mqtt,
    event_topic,
    log_topic,
    property_topic,
    publish_json,
)


def main() -> None:
    parser = build_arg_parser("模拟设备属性上行，便于在页面看到数值变化")
    parser.add_argument("--interval", type=float, default=3.0, help="上报间隔秒")
    parser.add_argument(
        "--rounds",
        type=int,
        default=0,
        help="上报轮数，0=一直跑直到 Ctrl+C",
    )
    parser.add_argument(
        "--with-event",
        action="store_true",
        help="每 5 轮额外上报一次事件（设备事件页可见）",
    )
    parser.add_argument(
        "--with-log",
        action="store_true",
        help="每 5 轮额外上报一次设备日志",
    )
    args = parser.parse_args()

    client = connect_mqtt(args)
    topic = property_topic(args.product, args.device)
    print("=" * 60)
    print("上行 Topic:", topic)
    print("请打开 Web → 设备详情 → 影子 / 运行状态 / 历史，观察数值变化")
    print("物模型属性建议包含: temperature / humidity / counter（无则影子 JSON 仍可见）")
    print("=" * 60)

    stop = {"flag": False}

    def _stop(*_):
        stop["flag"] = True

    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)

    i = 0
    while not stop["flag"]:
        i += 1
        # 正弦波动 + 计数，页面上很容易看出“在变”
        temperature = round(20 + 5 * math.sin(i / 3.0), 2)
        humidity = round(50 + 10 * math.cos(i / 4.0), 2)
        params = {
            "temperature": temperature,
            "humidity": humidity,
            "counter": i,
            "demoSource": "mqtt-demo/01_uplink_property.py",
        }
        payload = build_message(
            tenant_id=args.tenant_id,
            method="thing.property.post",
            params=params,
        )
        publish_json(client, topic, payload, qos=args.qos)

        if args.with_event and i % 5 == 0:
            evt = build_message(
                tenant_id=args.tenant_id,
                method="thing.event.post",
                params={"level": "INFO", "message": f"demo event #{i}", "counter": i},
            )
            publish_json(
                client,
                event_topic(args.product, args.device, "alarm"),
                evt,
                qos=args.qos,
            )

        if args.with_log and i % 5 == 0:
            log_payload = build_message(
                tenant_id=args.tenant_id,
                method="thing.log.post",
                params={"level": "INFO", "content": f"demo log line {i}"},
            )
            publish_json(
                client,
                log_topic(args.product, args.device),
                log_payload,
                qos=args.qos,
            )

        if args.rounds and i >= args.rounds:
            break
        time.sleep(args.interval)

    client.loop_stop()
    client.disconnect()
    print("[DONE] 上行演示结束")


if __name__ == "__main__":
    main()
