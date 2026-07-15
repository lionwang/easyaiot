package com.basiclab.iot.sink.service.device.impl;

import cn.hutool.core.lang.Assert;
import com.basiclab.iot.common.core.KeyValue;
import com.basiclab.iot.common.core.context.TenantContextHolder;
import com.basiclab.iot.common.core.util.TenantUtils;
import com.basiclab.iot.sink.biz.dto.IotDeviceRespDTO;
import com.basiclab.iot.sink.dal.dataobject.DeviceDO;
import com.basiclab.iot.sink.dal.mapper.DeviceMapper;
import com.basiclab.iot.sink.service.device.DeviceService;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Duration;

import static com.basiclab.iot.common.utils.cache.CacheUtils.buildAsyncReloadingCache;

/**
 * DeviceServiceImpl
 *
 * @author 翱翔的雄库鲁
 * @email andywebjava@163.com
 * @wechat EasyAIoT2025
 */

@Service
@Slf4j
public class DeviceServiceImpl implements DeviceService {

    private static final Duration CACHE_EXPIRE = Duration.ofMinutes(1);

    /**
     * 通过 id 查询设备的缓存
     */
    private final LoadingCache<TenantDeviceIdKey, IotDeviceRespDTO> deviceCaches = buildAsyncReloadingCache(
            CACHE_EXPIRE,
            new CacheLoader<TenantDeviceIdKey, IotDeviceRespDTO>() {
                @Override
                public IotDeviceRespDTO load(TenantDeviceIdKey key) {
                    return TenantUtils.execute(key.getTenantId(), () -> {
                        Long id = key.getDeviceId();
                        DeviceDO deviceDO = deviceMapper.selectById(id);
                    Assert.notNull(deviceDO, "设备({}) 不能为空", id);
                        IotDeviceRespDTO device = convertToDTO(deviceDO);
                    // 相互缓存
                        deviceCaches2.put(new TenantDeviceKey(device.getTenantId(),
                                device.getProductIdentification(), device.getDeviceIdentification()), device);
                        return device;
                    });
                }
            });

    /**
     * 通过 productIdentification + deviceIdentification 查询设备的缓存
     */
    private final LoadingCache<TenantDeviceKey, IotDeviceRespDTO> deviceCaches2 = buildAsyncReloadingCache(
            CACHE_EXPIRE,
            new CacheLoader<TenantDeviceKey, IotDeviceRespDTO>() {
                @Override
                public IotDeviceRespDTO load(TenantDeviceKey key) {
                    return TenantUtils.execute(key.getTenantId(), () -> {
                        KeyValue<String, String> kv = new KeyValue<>(
                                key.getProductIdentification(), key.getDeviceIdentification());
                        DeviceDO deviceDO = deviceMapper.selectByProductIdentificationAndDeviceIdentification(
                                kv.getKey(), kv.getValue());
                    Assert.notNull(deviceDO, "设备({}/{}) 不能为空", kv.getKey(), kv.getValue());
                        IotDeviceRespDTO device = convertToDTO(deviceDO);
                    // 相互缓存
                        deviceCaches.put(new TenantDeviceIdKey(device.getTenantId(), device.getId()), device);
                        return device;
                    });
                }
            });

    @Resource
    private DeviceMapper deviceMapper;

    @Override
    public IotDeviceRespDTO getDevice(String productIdentification, String deviceIdentification) {
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        return deviceCaches2.getUnchecked(new TenantDeviceKey(
                tenantId, productIdentification, deviceIdentification));
    }

    @Override
    public IotDeviceRespDTO getDevice(Long id) {
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        return deviceCaches.getUnchecked(new TenantDeviceIdKey(tenantId, id));
    }

    @Override
    public DeviceDO getDeviceForAuth(String clientId, String userName, String password, String deviceStatus, String protocolType) {
        return deviceMapper.selectByClientIdAndUserNameAndPasswordAndDeviceStatusAndProtocolType(
                clientId, userName, password, deviceStatus, protocolType);
    }

    /**
     * 将 DeviceDO 转换为 IotDeviceRespDTO
     */
    private IotDeviceRespDTO convertToDTO(DeviceDO deviceDO) {
        IotDeviceRespDTO dto = new IotDeviceRespDTO();
        dto.setId(deviceDO.getId());
        dto.setProductIdentification(deviceDO.getProductIdentification());
        dto.setDeviceIdentification(deviceDO.getDeviceIdentification());
        dto.setProtocolType(deviceDO.getProtocolType());
        dto.setIpAddress(deviceDO.getIpAddress());
        dto.setExtension(deviceDO.getExtension());
        dto.setTenantId(deviceDO.getTenantId());
        return dto;
    }

    @Override
    public DeviceDO getDeviceForProtocolAuth(String clientId, String productIdentification, String deviceIdentification,
                                             String deviceStatus, String protocolType) {
        return deviceMapper.selectDeviceForAuth(clientId, productIdentification, deviceIdentification,
                deviceStatus, protocolType);
    }

    @Value
    private static class TenantDeviceIdKey {
        Long tenantId;
        Long deviceId;
    }

    @Value
    private static class TenantDeviceKey {
        Long tenantId;
        String productIdentification;
        String deviceIdentification;
    }
}

