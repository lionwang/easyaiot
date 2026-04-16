package com.basiclab.iot.sink.service.ods;

import com.basiclab.iot.sink.domain.model.AlertNotificationMessage;

/**
 * 算法 ODS 下沉服务接口。
 */
public interface AlgorithmOdsSinkService {

    /**
     * 支持的检测类型，例如 face / plate。
     */
    String supportDetectionType();

    /**
     * 执行 ODS 下沉。
     */
    boolean sink(AlertNotificationMessage message, String sourceTopic, int partition, long offset);
}
