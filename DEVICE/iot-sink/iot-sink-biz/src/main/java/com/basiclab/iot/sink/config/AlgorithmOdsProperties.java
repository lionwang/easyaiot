package com.basiclab.iot.sink.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 算法 ODS 下沉配置。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "spring.kafka.algorithm-ods")
public class AlgorithmOdsProperties {

    private DorisProperties doris = new DorisProperties();
    private FaceProperties face = new FaceProperties();
    private PlateProperties plate = new PlateProperties();

    @Data
    public static class DorisProperties {
        /**
         * Doris MySQL 协议 JDBC 地址，默认连接 FE 9030 端口。
         */
        private String jdbcUrl = "jdbc:mysql://localhost:9030/easyaiot_person_vehicle_analytics_dw?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai";
        private String username = "root";
        private String password = "";
    }

    @Data
    public static class FaceProperties {
        private String dispatchTopic = "iot-algorithm-face-ods-dispatch";
        private String groupId = "iot-sink-face-ods-sink-consumer";
        private String odsTable = "ods_face_event";
    }

    @Data
    public static class PlateProperties {
        private String dispatchTopic = "iot-algorithm-plate-ods-dispatch";
        private String groupId = "iot-sink-plate-ods-sink-consumer";
        private String odsTable = "ods_plate_event";
    }
}
