"""
与 VIDEO 实时算法任务一致的检测框绘制样式（run_deploy.draw_detections）。
"""
from __future__ import annotations

from typing import Any, Dict, List, Optional

import cv2
import numpy as np

# 与 VIDEO/services/realtime_algorithm_service/run_deploy.py 保持一致
ALGORITHM_BOX_COLOR = (0, 255, 0)  # BGR 亮绿色
ALGORITHM_BOX_THICKNESS = 2
ALGORITHM_FONT_SCALE = 0.8
ALGORITHM_FONT_THICKNESS = 2
ALGORITHM_DEFAULT_CONF = 0.25


def yolo_results_to_detections(result) -> List[Dict[str, Any]]:
    """Ultralytics Results -> 算法任务通用 detection 列表。"""
    detections: List[Dict[str, Any]] = []
    boxes = getattr(result, 'boxes', None)
    if boxes is None or len(boxes) == 0:
        return detections
    names = getattr(result, 'names', {}) or {}
    for box in boxes:
        cls_id = int(box.cls.item())
        detections.append({
            'bbox': box.xyxy.tolist()[0],
            'class_name': names.get(cls_id, str(cls_id)),
            'confidence': float(box.conf.item()),
        })
    return detections


def draw_algorithm_detections(
    frame: np.ndarray,
    detections: Optional[List[Dict[str, Any]]],
    *,
    tracking_enabled: bool = False,
) -> np.ndarray:
    """在帧上绘制检测结果（样式对齐 VIDEO 算法任务）。"""
    if frame is None:
        return frame
    if not detections:
        return frame

    annotated_frame = frame.copy()
    h, w = annotated_frame.shape[:2]

    for det in detections:
        bbox = det.get('bbox') or []
        if not bbox or len(bbox) != 4:
            continue

        x1, y1, x2, y2 = bbox
        x1 = max(0, min(int(x1), w - 1))
        y1 = max(0, min(int(y1), h - 1))
        x2 = max(x1 + 1, min(int(x2), w))
        y2 = max(y1 + 1, min(int(y2), h))

        class_name = str(det.get('class_name') or 'unknown')
        track_id = det.get('track_id', 0)
        is_cached = bool(det.get('is_cached', False))

        if is_cached:
            color = (0, 200, 0)
            thickness = ALGORITHM_BOX_THICKNESS
            alpha = 0.7
        else:
            color = ALGORITHM_BOX_COLOR
            thickness = ALGORITHM_BOX_THICKNESS
            alpha = 1.0

        if is_cached:
            overlay = annotated_frame.copy()
            cv2.rectangle(overlay, (x1, y1), (x2, y2), color, thickness)
            cv2.addWeighted(overlay, alpha, annotated_frame, 1 - alpha, 0, annotated_frame)
        else:
            cv2.rectangle(annotated_frame, (x1, y1), (x2, y2), color, thickness)

        if tracking_enabled:
            text = f'ID:{track_id} {class_name}'
        else:
            text = class_name

        (text_width, text_height), _baseline = cv2.getTextSize(
            text,
            cv2.FONT_HERSHEY_SIMPLEX,
            ALGORITHM_FONT_SCALE,
            ALGORITHM_FONT_THICKNESS,
        )
        text_x = x1
        text_y = max(text_height + 5, y1 - 5)

        if not text.isascii():
            from app.utils.yolo_chinese_font import draw_utf8_label_on_bgr
            pil_y = max(0, text_y - text_height)
            if not draw_utf8_label_on_bgr(
                annotated_frame,
                text,
                (text_x, pil_y),
                font_size=22,
                text_color_rgb=(color[2], color[1], color[0]),
            ):
                cv2.putText(
                    annotated_frame,
                    text,
                    (text_x, text_y),
                    cv2.FONT_HERSHEY_SIMPLEX,
                    ALGORITHM_FONT_SCALE,
                    color,
                    ALGORITHM_FONT_THICKNESS,
                )
        else:
            cv2.putText(
                annotated_frame,
                text,
                (text_x, text_y),
                cv2.FONT_HERSHEY_SIMPLEX,
                ALGORITHM_FONT_SCALE,
                color,
                ALGORITHM_FONT_THICKNESS,
            )

    return annotated_frame
