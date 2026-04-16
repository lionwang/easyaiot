package com.basiclab.iot.sink.service.ods;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 算法 ODS 下沉服务工厂。
 */
@Component
public class AlgorithmOdsSinkServiceFactory {

    private final Map<String, AlgorithmOdsSinkService> serviceMap = new HashMap<>();

    public AlgorithmOdsSinkServiceFactory(List<AlgorithmOdsSinkService> services) {
        for (AlgorithmOdsSinkService service : services) {
            String detectionType = normalize(service.supportDetectionType());
            if (StringUtils.hasText(detectionType)) {
                serviceMap.put(detectionType, service);
            }
        }
    }

    public AlgorithmOdsSinkService getService(String detectionType) {
        return serviceMap.get(normalize(detectionType));
    }

    private String normalize(String detectionType) {
        if (!StringUtils.hasText(detectionType)) {
            return "";
        }
        return detectionType.trim().toLowerCase(Locale.ROOT);
    }
}
