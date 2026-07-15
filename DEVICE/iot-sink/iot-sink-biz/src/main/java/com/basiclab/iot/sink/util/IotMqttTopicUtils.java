package com.basiclab.iot.sink.util;

import cn.hutool.core.util.StrUtil;
import com.basiclab.iot.sink.enums.IotDeviceMessageMethodEnum;
import com.basiclab.iot.sink.enums.IotDeviceTopicEnum;

/**
 * IotMqttTopicUtils
 *
 * 下行主题统一使用 /iot/{product}/{device}/... 标准（与 IotDeviceTopicEnum、EMQX 订阅对齐）。
 */
public final class IotMqttTopicUtils {

    public static final String MQTT_AUTH_PATH = "/mqtt/auth";

    public static final String MQTT_EVENT_PATH = "/mqtt/event";

    private IotMqttTopicUtils() {
    }

    /**
     * 根据消息方法构建对应的主题（标准 /iot 体系）
     */
    public static String buildTopicByMethod(String method, String productIdentification,
                                            String deviceIdentification, boolean isReply) {
        if (StrUtil.isBlank(method) || StrUtil.hasBlank(productIdentification, deviceIdentification)) {
            return null;
        }

        IotDeviceMessageMethodEnum methodEnum = IotDeviceMessageMethodEnum.of(method);
        if (methodEnum == null) {
            return null;
        }

        IotDeviceTopicEnum topicEnum;
        switch (methodEnum) {
            case PROPERTY_SET:
                topicEnum = isReply
                        ? IotDeviceTopicEnum.PROPERTY_UPSTREAM_DESIRED_SET_ACK
                        : IotDeviceTopicEnum.PROPERTY_DOWNSTREAM_DESIRED_SET;
                break;
            case PROPERTY_POST:
                topicEnum = isReply
                        ? IotDeviceTopicEnum.PROPERTY_DOWNSTREAM_REPORT_ACK
                        : IotDeviceTopicEnum.PROPERTY_UPSTREAM_REPORT;
                break;
            case SERVICE_INVOKE:
                topicEnum = isReply
                        ? IotDeviceTopicEnum.SERVICE_UPSTREAM_INVOKE_RESPONSE
                        : IotDeviceTopicEnum.SERVICE_DOWNSTREAM_INVOKE;
                break;
            case EVENT_POST:
                topicEnum = isReply
                        ? IotDeviceTopicEnum.EVENT_DOWNSTREAM_REPORT_ACK
                        : IotDeviceTopicEnum.EVENT_UPSTREAM_REPORT;
                break;
            case CONFIG_PUSH:
                topicEnum = isReply
                        ? IotDeviceTopicEnum.CONFIG_DOWNSTREAM_QUERY_ACK
                        : IotDeviceTopicEnum.CONFIG_DOWNSTREAM_PUSH;
                break;
            case OTA_UPGRADE:
                topicEnum = IotDeviceTopicEnum.OTA_DOWNSTREAM_UPGRADE_TASK;
                break;
            case OTA_PROGRESS:
                topicEnum = IotDeviceTopicEnum.OTA_UPSTREAM_PROGRESS_REPORT;
                break;
            case LOG_POST:
                topicEnum = isReply
                        ? IotDeviceTopicEnum.LOG_DOWNSTREAM_REPORT_ACK
                        : IotDeviceTopicEnum.LOG_UPSTREAM_REPORT;
                break;
            default:
                return null;
        }
        return topicEnum.buildTopic(productIdentification, deviceIdentification);
    }
}
