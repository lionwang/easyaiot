package com.basiclab.iot.sink.consumer;

import com.basiclab.iot.common.utils.json.JsonUtils;
import com.basiclab.iot.sink.domain.model.AlertNotificationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 算法ODS下沉监听器：
 * 监听人脸/车牌分流队列，转换为ODS贴源事件后直接下沉 Doris ODS 表。
 */
@Slf4j
@Component
public class AlgorithmOdsSinkConsumer {

    private static final String DETECTION_FACE = "face";
    private static final String DETECTION_PLATE = "plate";
    private static final DateTimeFormatter ALERT_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");

    @Value("${spring.kafka.algorithm-ods.doris.fe-nodes:localhost:8030}")
    private String dorisFeNodes;

    @Value("${spring.kafka.algorithm-ods.doris.database:iot_device_ods}")
    private String dorisDatabase;

    @Value("${spring.kafka.algorithm-ods.doris.username:root}")
    private String dorisUsername;

    @Value("${spring.kafka.algorithm-ods.doris.password:}")
    private String dorisPassword;

    @Value("${spring.kafka.algorithm-ods.face.ods-table:ods_face_event}")
    private String faceOdsTable;

    @Value("${spring.kafka.algorithm-ods.plate.ods-table:ods_plate_event}")
    private String plateOdsTable;

    @KafkaListener(
            topics = "${spring.kafka.algorithm-ods.face.dispatch-topic:iot-algorithm-face-ods-dispatch}",
            groupId = "${spring.kafka.algorithm-ods.face.group-id:iot-sink-face-ods-sink-consumer}",
            containerFactory = "iotKafkaListenerContainerFactory"
    )
    public void consumeFaceDispatch(
            @Payload String messageJson,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION_ID) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {
        processAndSink(messageJson, DETECTION_FACE, topic, partition, offset, acknowledgment);
    }

    @KafkaListener(
            topics = "${spring.kafka.algorithm-ods.plate.dispatch-topic:iot-algorithm-plate-ods-dispatch}",
            groupId = "${spring.kafka.algorithm-ods.plate.group-id:iot-sink-plate-ods-sink-consumer}",
            containerFactory = "iotKafkaListenerContainerFactory"
    )
    public void consumePlateDispatch(
            @Payload String messageJson,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION_ID) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {
        processAndSink(messageJson, DETECTION_PLATE, topic, partition, offset, acknowledgment);
    }

    private void processAndSink(String messageJson,
                                String detectionType,
                                String sourceTopic,
                                int partition,
                                long offset,
                                Acknowledgment acknowledgment) {
        try {
            if (!StringUtils.hasText(messageJson)) {
                ack(acknowledgment);
                return;
            }

            AlertNotificationMessage message = JsonUtils.parseObject(messageJson, AlertNotificationMessage.class);
            if (message == null || message.getAlert() == null) {
                ack(acknowledgment);
                return;
            }

            List<Map<String, Object>> detections = extractDetections(message);
            if (detections.isEmpty()) {
                log.debug("消息中无检测结果，跳过ODS下沉: detectionType={}, sourceTopic={}, deviceId={}",
                        detectionType, sourceTopic, message.getDeviceId());
                ack(acknowledgment);
                return;
            }

            long ts = parseTimestampMillis(message.getAlert().getTime());
            List<Map<String, Object>> odsEvents = new ArrayList<>();
            for (Map<String, Object> detection : detections) {
                String className = asString(detection.get("class_name"));
                if (DETECTION_FACE.equals(detectionType) && !isFaceClass(className)) {
                    continue;
                }
                if (DETECTION_PLATE.equals(detectionType) && !isPlateClass(className)) {
                    continue;
                }

                Map<String, Object> odsEvent = DETECTION_FACE.equals(detectionType)
                        ? buildFaceOdsEvent(message, detection, ts)
                        : buildPlateOdsEvent(message, detection, ts);
                odsEvents.add(odsEvent);
            }

            if (odsEvents.isEmpty()) {
                log.debug("过滤后无可下沉事件，跳过Doris写入: detectionType={}, sourceTopic={}, deviceId={}",
                        detectionType, sourceTopic, message.getDeviceId());
                ack(acknowledgment);
                return;
            }

            String tableName = DETECTION_FACE.equals(detectionType) ? faceOdsTable : plateOdsTable;
            boolean success = streamLoadToDoris(tableName, odsEvents);
            if (success) {
                log.info("算法ODS下沉完成: detectionType={}, sourceTopic={}, partition={}, offset={}, deviceId={}, written={}",
                        detectionType, sourceTopic, partition, offset, message.getDeviceId(), odsEvents.size());
                ack(acknowledgment);
            } else {
                log.error("算法ODS下沉失败: detectionType={}, sourceTopic={}, partition={}, offset={}, deviceId={}, table={}, rows={}",
                        detectionType, sourceTopic, partition, offset, message.getDeviceId(), tableName, odsEvents.size());
            }
        } catch (Exception e) {
            log.error("算法ODS下沉失败: detectionType={}, sourceTopic={}, partition={}, offset={}, error={}",
                    detectionType, sourceTopic, partition, offset, e.getMessage(), e);
        }
    }

    private boolean streamLoadToDoris(String tableName, List<Map<String, Object>> events) {
        HttpURLConnection connection = null;
        try {
            String feNode = resolvePrimaryFeNode(dorisFeNodes);
            if (!StringUtils.hasText(feNode)) {
                log.error("Doris FE 节点配置为空，无法下沉: feNodes={}", dorisFeNodes);
                return false;
            }

            String url = String.format("http://%s/api/%s/%s/_stream_load", feNode, dorisDatabase, tableName);
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("PUT");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("Authorization", buildBasicAuth(dorisUsername, dorisPassword));
            connection.setRequestProperty("label", buildStreamLoadLabel(tableName));
            connection.setRequestProperty("format", "json");
            connection.setRequestProperty("strip_outer_array", "true");
            connection.setRequestProperty("Expect", "100-continue");

            byte[] payload = JsonUtils.toJsonString(events).getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(payload);
                outputStream.flush();
            }

            int statusCode = connection.getResponseCode();
            String responseBody = readResponseBody(connection, statusCode);
            if (statusCode < 200 || statusCode >= 300) {
                log.error("Doris Stream Load HTTP失败: table={}, statusCode={}, body={}", tableName, statusCode, responseBody);
                return false;
            }

            Map<String, Object> result = JsonUtils.parseObject(responseBody, Map.class);
            String status = result != null ? asString(result.get("Status")) : null;
            if (!"Success".equalsIgnoreCase(status) && !"Publish Timeout".equalsIgnoreCase(status)) {
                log.error("Doris Stream Load 返回失败: table={}, status={}, body={}", tableName, status, responseBody);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("Doris Stream Load 异常: table={}, error={}", tableName, e.getMessage(), e);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String resolvePrimaryFeNode(String nodes) {
        if (!StringUtils.hasText(nodes)) {
            return "";
        }
        String[] split = nodes.split(",");
        return split.length > 0 ? split[0].trim() : nodes.trim();
    }

    private String buildBasicAuth(String username, String password) {
        String user = StringUtils.hasText(username) ? username : "root";
        String pass = password == null ? "" : password;
        String token = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    private String buildStreamLoadLabel(String tableName) {
        return "iot_sink_" + tableName + "_" + System.currentTimeMillis() + "_" + UUID.randomUUID();
    }

    private String readResponseBody(HttpURLConnection connection, int statusCode) {
        InputStream inputStream = null;
        try {
            inputStream = statusCode >= 200 && statusCode < 300 ? connection.getInputStream() : connection.getErrorStream();
            if (inputStream == null) {
                return "";
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int read;
            while ((read = inputStream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private List<Map<String, Object>> extractDetections(AlertNotificationMessage message) {
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

        Map<String, Object> single = new HashMap<>();
        single.put("class_name", message.getAlert().getObject());
        single.put("track_id", infoMap.get("track_id"));
        single.put("confidence", infoMap.get("confidence"));
        single.put("bbox", infoMap.get("bbox"));
        detections.add(single);
        return detections;
    }

    private Map<String, Object> buildFaceOdsEvent(AlertNotificationMessage message, Map<String, Object> detection, long ts) {
        int[] bbox = parseBbox(detection.get("bbox"));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", UUID.randomUUID().toString());
        event.put("event_type", "face_detection");
        event.put("device_id", message.getDeviceId());
        event.put("ts", ts);
        event.put("track_id", asString(detection.get("track_id")));
        event.put("bbox_x", bbox[0]);
        event.put("bbox_y", bbox[1]);
        event.put("bbox_w", bbox[2]);
        event.put("bbox_h", bbox[3]);
        event.put("score", toDouble(detection.get("confidence")));
        event.put("face_quality", toDouble(detection.get("confidence")));
        return event;
    }

    private Map<String, Object> buildPlateOdsEvent(AlertNotificationMessage message, Map<String, Object> detection, long ts) {
        int[] bbox = parseBbox(detection.get("bbox"));
        String className = asString(detection.get("class_name"));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", UUID.randomUUID().toString());
        event.put("event_type", "plate_ocr");
        event.put("device_id", message.getDeviceId());
        event.put("ts", ts);
        event.put("track_id", asString(detection.get("track_id")));
        event.put("bbox_x", bbox[0]);
        event.put("bbox_y", bbox[1]);
        event.put("bbox_w", bbox[2]);
        event.put("bbox_h", bbox[3]);
        event.put("score", toDouble(detection.get("confidence")));
        event.put("plate_no", asString(detection.get("plate_no")));
        event.put("plate_score", toDouble(detection.get("confidence")));
        event.put("plate_color", asString(detection.get("plate_color")));
        event.put("vehicle_type", className);
        event.put("vehicle_color", asString(detection.get("vehicle_color")));
        event.put("vehicle_brand", asString(detection.get("vehicle_brand")));
        return event;
    }

    private Map<String, Object> toMap(Object value) {
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

    private int[] parseBbox(Object bboxObj) {
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

    private long parseTimestampMillis(String timeText) {
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

    private boolean isFaceClass(String className) {
        String normalized = normalizeClassName(className);
        return normalized.contains("face") || normalized.contains("facial") || normalized.contains("person_face")
                || normalized.contains("人脸");
    }

    private boolean isPlateClass(String className) {
        String normalized = normalizeClassName(className);
        return normalized.contains("plate") || normalized.contains("license_plate")
                || normalized.contains("licence_plate") || normalized.contains("car_plate")
                || normalized.contains("车牌");
    }

    private String normalizeClassName(String className) {
        if (!StringUtils.hasText(className)) {
            return "";
        }
        return className.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return 0D;
        }
    }

    private void ack(Acknowledgment acknowledgment) {
        if (acknowledgment != null) {
            acknowledgment.acknowledge();
        }
    }
}
