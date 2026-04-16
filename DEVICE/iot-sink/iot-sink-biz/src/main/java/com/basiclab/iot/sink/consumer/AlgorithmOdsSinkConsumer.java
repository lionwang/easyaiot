package com.basiclab.iot.sink.consumer;

import com.basiclab.iot.common.utils.json.JsonUtils;
import com.basiclab.iot.sink.config.AlgorithmOdsProperties;
import com.basiclab.iot.sink.domain.model.AlertNotificationMessage;
import com.basiclab.iot.sink.service.ods.AlgorithmOdsSinkService;
import com.basiclab.iot.sink.service.ods.AlgorithmOdsSinkServiceFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 算法ODS下沉监听器：
 * 监听人脸/车牌分流队列，并通过工厂模式路由到对应 ODS 处理器。
 */
@Slf4j
@Component
public class AlgorithmOdsSinkConsumer {

    private static final String DETECTION_FACE = "face";
    private static final String DETECTION_PLATE = "plate";

    private final AlgorithmOdsSinkServiceFactory sinkServiceFactory;
    private final String faceDispatchTopic;
    private final String faceGroupId;
    private final String plateDispatchTopic;
    private final String plateGroupId;

    public AlgorithmOdsSinkConsumer(AlgorithmOdsSinkServiceFactory sinkServiceFactory,
                                    AlgorithmOdsProperties algorithmOdsProperties) {
        this.sinkServiceFactory = sinkServiceFactory;
        this.faceDispatchTopic = algorithmOdsProperties.getFace().getDispatchTopic();
        this.faceGroupId = algorithmOdsProperties.getFace().getGroupId();
        this.plateDispatchTopic = algorithmOdsProperties.getPlate().getDispatchTopic();
        this.plateGroupId = algorithmOdsProperties.getPlate().getGroupId();
    }

    public String getFaceDispatchTopic() {
        return faceDispatchTopic;
    }

    public String getFaceGroupId() {
        return faceGroupId;
    }

    public String getPlateDispatchTopic() {
        return plateDispatchTopic;
    }

    public String getPlateGroupId() {
        return plateGroupId;
    }

    @KafkaListener(
            topics = "#{__listener.faceDispatchTopic}",
            groupId = "#{__listener.faceGroupId}",
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
            topics = "#{__listener.plateDispatchTopic}",
            groupId = "#{__listener.plateGroupId}",
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

            AlgorithmOdsSinkService sinkService = sinkServiceFactory.getService(detectionType);
            if (sinkService == null) {
                log.error("未找到算法ODS下沉服务: detectionType={}, sourceTopic={}, partition={}, offset={}",
                        detectionType, sourceTopic, partition, offset);
                ack(acknowledgment);
                return;
            }

            boolean success = sinkService.sink(message, sourceTopic, partition, offset);
            if (success) {
                ack(acknowledgment);
            }
        } catch (Exception e) {
            log.error("算法ODS下沉失败: detectionType={}, sourceTopic={}, partition={}, offset={}, error={}",
                    detectionType, sourceTopic, partition, offset, e.getMessage(), e);
        }
    }

    private void ack(Acknowledgment acknowledgment) {
        if (acknowledgment != null) {
            acknowledgment.acknowledge();
        }
    }
}
