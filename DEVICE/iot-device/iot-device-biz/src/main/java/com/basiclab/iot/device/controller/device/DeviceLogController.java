package com.basiclab.iot.device.controller.device;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.basiclab.iot.common.constant.HttpStatus;
import com.basiclab.iot.common.domain.TableDataInfo;
import com.basiclab.iot.common.utils.StringUtils;
import com.basiclab.iot.device.domain.device.vo.Device;
import com.basiclab.iot.device.service.device.DeviceService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Device-originated MQTT log query endpoints.
 */
@Api(tags = "Device MQTT Log")
@RestController
@RequestMapping("/device/log")
@RequiredArgsConstructor
public class DeviceLogController {

    private final DeviceService deviceService;

    @ApiOperation("List device-originated MQTT logs")
    @GetMapping("/{deviceId}")
    public TableDataInfo list(@PathVariable Long deviceId,
                              @RequestParam(defaultValue = "1") Integer page,
                              @RequestParam(defaultValue = "10") Integer pageSize,
                              @RequestParam(required = false) String actionType,
                              @RequestParam(required = false) String userName,
                              @RequestParam(required = false) String status) {
        Device device = deviceService.findOneById(deviceId);
        if (device == null || StringUtils.isEmpty(device.getExtension())) {
            return tableData(Collections.emptyList(), 0);
        }

        JSONObject extension = JSONObject.parseObject(device.getExtension());
        JSONArray storedLogs = extension.getJSONArray("logs");
        if (storedLogs == null || storedLogs.isEmpty()) {
            return tableData(Collections.emptyList(), 0);
        }

        List<Map> logs = new ArrayList<>(storedLogs.toJavaList(Map.class));
        Collections.reverse(logs);
        List<Map> filtered = logs.stream()
                .filter(log -> matchesValue(log.get("actionType"), actionType))
                .filter(log -> matchesText(log.get("userName"), userName))
                .filter(log -> matchesValue(log.get("status"), status))
                .collect(Collectors.toList());

        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 1000);
        int from = Math.min((safePage - 1) * safePageSize, filtered.size());
        int to = Math.min(from + safePageSize, filtered.size());
        return tableData(filtered.subList(from, to), filtered.size());
    }

    private boolean matchesValue(Object value, String expected) {
        return StringUtils.isEmpty(expected) || expected.equalsIgnoreCase(String.valueOf(value));
    }

    private boolean matchesText(Object value, String expected) {
        return StringUtils.isEmpty(expected) || value != null
                && String.valueOf(value).toLowerCase().contains(expected.toLowerCase());
    }

    private TableDataInfo tableData(List<?> data, long total) {
        TableDataInfo result = new TableDataInfo();
        result.setCode(HttpStatus.SUCCESS);
        result.setMsg("Query successful");
        result.setData(data);
        result.setTotal(total);
        return result;
    }
}
