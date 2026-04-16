package com.basiclab.iot.sink.service.ods.impl;

import com.basiclab.iot.sink.config.AlgorithmOdsProperties;
import com.basiclab.iot.sink.domain.model.AlertNotificationMessage;
import com.basiclab.iot.sink.service.doris.DorisJdbcSinkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 车牌算法 ODS 下沉服务。
 */
@Slf4j
@Service
public class PlateAlgorithmOdsSinkServiceImpl extends AbstractAlgorithmOdsSinkService {

    private static final String DETECTION_TYPE = "plate";

    private final AlgorithmOdsProperties algorithmOdsProperties;

    public PlateAlgorithmOdsSinkServiceImpl(DorisJdbcSinkService dorisJdbcSinkService,
                                            AlgorithmOdsProperties algorithmOdsProperties) {
        super(dorisJdbcSinkService);
        this.algorithmOdsProperties = algorithmOdsProperties;
    }

    @Override
    public String supportDetectionType() {
        return DETECTION_TYPE;
    }

    @Override
    public boolean sink(AlertNotificationMessage message, String sourceTopic, int partition, long offset) {
        List<Map<String, Object>> detections = extractDetections(message);
        if (detections.isEmpty()) {
            log.debug("消息中无检测结果，跳过ODS下沉: detectionType={}, sourceTopic={}, deviceId={}",
                    DETECTION_TYPE, sourceTopic, message.getDeviceId());
            return true;
        }

        long ts = parseTimestampMillis(message.getAlert().getTime());
        List<Map<String, Object>> odsEvents = new ArrayList<>();
        for (Map<String, Object> detection : detections) {
            String className = asString(detection.get("class_name"));
            if (!isPlateClass(className)) {
                continue;
            }
            odsEvents.add(buildPlateOdsEvent(message, detection, ts, partition, offset));
        }

        if (odsEvents.isEmpty()) {
            log.debug("过滤后无可下沉事件，跳过Doris写入: detectionType={}, sourceTopic={}, deviceId={}",
                    DETECTION_TYPE, sourceTopic, message.getDeviceId());
            return true;
        }

        String plateOdsTable = algorithmOdsProperties.getPlate().getOdsTable();
        boolean success = dorisJdbcSinkService.batchInsert(plateOdsTable, odsEvents);
        if (success) {
            log.info("算法ODS下沉完成: detectionType={}, sourceTopic={}, partition={}, offset={}, deviceId={}, written={}",
                    DETECTION_TYPE, sourceTopic, partition, offset, message.getDeviceId(), odsEvents.size());
        } else {
            log.error("算法ODS下沉失败: detectionType={}, sourceTopic={}, partition={}, offset={}, deviceId={}, table={}, rows={}",
                    DETECTION_TYPE, sourceTopic, partition, offset, message.getDeviceId(), plateOdsTable, odsEvents.size());
        }
        return success;
    }

    private Map<String, Object> buildPlateOdsEvent(AlertNotificationMessage message,
                                                    Map<String, Object> detection,
                                                    long ts,
                                                    int partition,
                                                    long offset) {
        int[] bbox = parseBbox(detection.get("bbox"));
        String className = asString(detection.get("class_name"));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", UUID.randomUUID().toString());
        event.put("event_type", "plate_ocr");
        event.put("device_id", message.getDeviceId());
        event.put("ts", ts);
        event.put("ingest_ts", System.currentTimeMillis());
        event.put("kafka_partition", partition);
        event.put("kafka_offset", offset);
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
}
