package com.basiclab.iot.sink.service.ods.impl;

import com.basiclab.iot.common.utils.json.JsonUtils;
import com.basiclab.iot.sink.domain.model.AlertNotificationMessage;
import com.basiclab.iot.sink.service.doris.DorisJdbcSinkService;
import com.basiclab.iot.sink.service.ods.AlgorithmOdsSinkService;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 算法 ODS 下沉服务公共能力。
 */
public abstract class AbstractAlgorithmOdsSinkService implements AlgorithmOdsSinkService {

    private static final DateTimeFormatter ALERT_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");

    protected final DorisJdbcSinkService dorisJdbcSinkService;

    protected AbstractAlgorithmOdsSinkService(DorisJdbcSinkService dorisJdbcSinkService) {
        this.dorisJdbcSinkService = dorisJdbcSinkService;
    }

    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> extractDetections(AlertNotificationMessage message) {
        List<Map<String, Object>> detections = new ArrayList<>();
        Object infoObj = message.getAlert().getInformation();
        Map<String, Object> infoMap = toMap(infoObj);
        Object nestedDetections = infoMap.get("detections");
        if (nestedDetections instanceof List) {
            for (Object item : (List<?>) nestedDetections) {
                if (item instanceof Map) {
                    detections.add((Map<String, Object>) item);
                }
            }
        }

        if (!detections.isEmpty()) {
            return detections;
        }

        Map<String, Object> single = new java.util.HashMap<>();
        single.put("class_name", message.getAlert().getObject());
        single.put("track_id", infoMap.get("track_id"));
        single.put("confidence", infoMap.get("confidence"));
        single.put("bbox", infoMap.get("bbox"));
        detections.add(single);
        return detections;
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> toMap(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (!StringUtils.hasText(text)) {
                return Collections.emptyMap();
            }
            try {
                Map<String, Object> parsed = JsonUtils.parseObject(text, Map.class);
                return parsed != null ? parsed : Collections.emptyMap();
            } catch (Exception ignored) {
                return Collections.emptyMap();
            }
        }
        return Collections.emptyMap();
    }

    protected int[] parseBbox(Object bboxObj) {
        int[] parsed = new int[]{0, 0, 0, 0};
        if (!(bboxObj instanceof List)) {
            return parsed;
        }
        List<?> bbox = (List<?>) bboxObj;
        if (bbox.size() < 4) {
            return parsed;
        }

        int x1 = toInt(bbox.get(0));
        int y1 = toInt(bbox.get(1));
        int x2 = toInt(bbox.get(2));
        int y2 = toInt(bbox.get(3));
        parsed[0] = x1;
        parsed[1] = y1;
        parsed[2] = Math.max(0, x2 - x1);
        parsed[3] = Math.max(0, y2 - y1);
        return parsed;
    }

    protected long parseTimestampMillis(String timeText) {
        if (!StringUtils.hasText(timeText)) {
            return System.currentTimeMillis();
        }
        try {
            LocalDateTime dateTime = LocalDateTime.parse(timeText, ALERT_TIME_FORMATTER);
            return dateTime.atZone(SHANGHAI_ZONE).toInstant().toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    protected boolean isFaceClass(String className) {
        String normalized = normalizeClassName(className);
        return normalized.contains("face") || normalized.contains("facial")
                || normalized.contains("person_face") || normalized.contains("人脸");
    }

    protected boolean isPlateClass(String className) {
        String normalized = normalizeClassName(className);
        return normalized.contains("plate") || normalized.contains("license_plate")
                || normalized.contains("licence_plate") || normalized.contains("car_plate")
                || normalized.contains("车牌");
    }

    protected String normalizeClassName(String className) {
        if (!StringUtils.hasText(className)) {
            return "";
        }
        return className.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    protected String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    protected int toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    protected double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return 0D;
        }
    }
}
