package com.basiclab.iot.device.controller.device;

import com.alibaba.fastjson2.JSONObject;
import com.basiclab.iot.common.domain.R;
import com.basiclab.iot.common.utils.StringUtils;
import com.basiclab.iot.device.domain.device.vo.Device;
import com.basiclab.iot.device.service.device.DeviceService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Device shadow query endpoints.
 */
@Api(tags = "Device Shadow")
@RestController
@RequestMapping("/shadow")
@RequiredArgsConstructor
public class DeviceShadowController {

    private final DeviceService deviceService;

    @ApiOperation("Get the latest device shadow")
    @GetMapping("/{deviceId}")
    public R<?> getDeviceShadow(@PathVariable Long deviceId) {
        Device device = deviceService.findOneById(deviceId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (device == null) {
            result.put("shadow", Collections.emptyMap());
            result.put("version", null);
            result.put("updateTime", null);
            return R.ok(result);
        }

        JSONObject extension = StringUtils.isEmpty(device.getExtension())
                ? new JSONObject() : JSONObject.parseObject(device.getExtension());
        Object shadow = extension.getOrDefault("shadow", Collections.emptyMap());
        Object version = shadow instanceof Map ? ((Map<?, ?>) shadow).get("version") : null;
        Object updateTime = extension.get("shadowUpdateTime");

        result.put("shadow", shadow);
        result.put("version", version);
        result.put("updateTime", updateTime != null ? updateTime : device.getUpdateTime());
        return R.ok(result);
    }
}
