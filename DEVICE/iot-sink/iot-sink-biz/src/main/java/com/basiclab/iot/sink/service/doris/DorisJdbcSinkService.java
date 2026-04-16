package com.basiclab.iot.sink.service.doris;

import com.basiclab.iot.sink.config.AlgorithmOdsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Doris JDBC（MySQL 协议）下沉服务。
 */
@Slf4j
@Service
public class DorisJdbcSinkService {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");

    private final AlgorithmOdsProperties algorithmOdsProperties;

    public DorisJdbcSinkService(AlgorithmOdsProperties algorithmOdsProperties) {
        this.algorithmOdsProperties = algorithmOdsProperties;
    }

    public boolean batchInsert(String tableName, List<Map<String, Object>> rows) {
        if (!StringUtils.hasText(tableName)) {
            log.error("Doris JDBC 写入失败，表名为空");
            return false;
        }
        if (rows == null || rows.isEmpty()) {
            return true;
        }
        String dorisJdbcUrl = algorithmOdsProperties.getDoris().getJdbcUrl();
        if (!StringUtils.hasText(dorisJdbcUrl)) {
            log.error("Doris JDBC 写入失败，jdbc-url 为空");
            return false;
        }
        String dorisUsername = algorithmOdsProperties.getDoris().getUsername();
        String dorisPassword = algorithmOdsProperties.getDoris().getPassword();

        String normalizedTable = tableName.trim();
        if (!isSafeIdentifier(normalizedTable)) {
            log.error("Doris JDBC 写入失败，非法表名: table={}", tableName);
            return false;
        }

        Map<String, Object> firstRow = rows.get(0);
        if (firstRow == null || firstRow.isEmpty()) {
            log.error("Doris JDBC 写入失败，首行数据为空: table={}", normalizedTable);
            return false;
        }

        List<String> columns = firstRow.keySet().stream()
                .map(String::trim)
                .collect(Collectors.toList());
        if (columns.isEmpty() || columns.stream().anyMatch(column -> !isSafeIdentifier(column))) {
            log.error("Doris JDBC 写入失败，列名非法: table={}, columns={}", normalizedTable, columns);
            return false;
        }

        String sql = buildInsertSql(normalizedTable, columns);
        try (Connection connection = DriverManager.getConnection(dorisJdbcUrl,
                StringUtils.hasText(dorisUsername) ? dorisUsername : "root",
                dorisPassword == null ? "" : dorisPassword);
             PreparedStatement statement = connection.prepareStatement(sql)) {

            for (Map<String, Object> row : rows) {
                for (int i = 0; i < columns.size(); i++) {
                    statement.setObject(i + 1, row.get(columns.get(i)));
                }
                statement.addBatch();
            }
            int[] affectedRows = statement.executeBatch();
            int successCount = countSuccess(affectedRows);
            if (successCount != rows.size()) {
                log.error("Doris JDBC 批量写入条数不一致: table={}, expected={}, actual={}",
                        normalizedTable, rows.size(), successCount);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("Doris JDBC 写入异常: table={}, error={}", normalizedTable, e.getMessage(), e);
            return false;
        }
    }

    private int countSuccess(int[] affectedRows) {
        int success = 0;
        for (int affected : affectedRows) {
            if (affected >= 0 || affected == PreparedStatement.SUCCESS_NO_INFO) {
                success++;
            }
        }
        return success;
    }

    private String buildInsertSql(String tableName, List<String> columns) {
        String columnPart = columns.stream()
                .map(column -> "`" + column + "`")
                .collect(Collectors.joining(", "));
        String placeholderPart = columns.stream()
                .map(column -> "?")
                .collect(Collectors.joining(", "));
        return "INSERT INTO `" + tableName + "` (" + columnPart + ") VALUES (" + placeholderPart + ")";
    }

    private boolean isSafeIdentifier(String identifier) {
        return StringUtils.hasText(identifier) && IDENTIFIER_PATTERN.matcher(identifier).matches();
    }
}
