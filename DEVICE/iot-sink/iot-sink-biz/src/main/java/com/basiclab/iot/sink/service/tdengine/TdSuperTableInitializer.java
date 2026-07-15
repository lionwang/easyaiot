package com.basiclab.iot.sink.service.tdengine;

import com.baomidou.dynamic.datasource.annotation.DS;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 启动时确保 MQTT 上行所需的 TDengine 超级表存在（与 .scripts/tdengine/tdengine_super_tables.sql 对齐）。
 * 子表由 INSERT ... USING superTable TAGS(...) 自动创建。
 */
@Slf4j
@Component
@DS("tdengine")
public class TdSuperTableInitializer implements ApplicationRunner {

    private static final String DB = "iot_device";

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    @DS("tdengine")
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute("CREATE DATABASE IF NOT EXISTS " + DB);
            createStable("st_property_upstream_report", false, true);
            createStable("st_property_upstream_desired_set_ack", false, false);
            createStable("st_property_upstream_desired_query_response", false, false);
            createStable("st_event_upstream_report", true, true);
            createStable("st_service_upstream_invoke_response", true, false);
            createStable("st_device_tag_upstream_report", false, true);
            createStable("st_device_tag_upstream_delete", false, true);
            createStable("st_shadow_upstream_report", false, true);
            createStable("st_config_upstream_query", false, true);
            createStable("st_ntp_upstream_request", false, true);
            createStable("st_ota_upstream_version_report", false, true);
            createStable("st_ota_upstream_progress_report", false, true);
            createStable("st_ota_upstream_firmware_query", false, true);
            createStable("st_log_upstream_report", false, true);
            log.info("[TdSuperTableInitializer][TDengine 超级表初始化完成]");
        } catch (Exception e) {
            log.error("[TdSuperTableInitializer][TDengine 超级表初始化失败，请确认 tdengine 数据源与服务可用]", e);
        }
    }

    /**
     * @param withIdentifierTag 事件/服务响应需要 identifier TAG
     * @param withParams        ACK/RESPONSE 类表通常无 params 列
     */
    private void createStable(String name, boolean withIdentifierTag, boolean withParams) {
        StringBuilder cols = new StringBuilder();
        cols.append("ts TIMESTAMP, ")
                .append("report_time TIMESTAMP, ")
                .append("device_id BIGINT, ")
                .append("server_id NCHAR(50), ")
                .append("request_id NCHAR(100), ")
                .append("method NCHAR(100), ");
        if (withParams) {
            cols.append("params NCHAR(5000), ");
        }
        cols.append("data NCHAR(5000), ")
                .append("code INT, ")
                .append("msg NCHAR(500), ")
                .append("`topic` NCHAR(500)");

        String tags = "device_identification NCHAR(128), "
                + "tenant_id BIGINT, "
                + "product_identification NCHAR(128)";
        if (withIdentifierTag) {
            tags += ", identifier NCHAR(100)";
        }

        String sql = "CREATE STABLE IF NOT EXISTS " + DB + "." + name
                + " (" + cols + ") TAGS (" + tags + ")";
        jdbcTemplate.execute(sql);
    }
}
