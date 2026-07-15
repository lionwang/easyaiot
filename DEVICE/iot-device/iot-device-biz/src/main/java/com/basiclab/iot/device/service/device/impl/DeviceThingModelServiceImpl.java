package com.basiclab.iot.device.service.device.impl;

import cn.hutool.core.bean.BeanUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.basiclab.iot.common.domain.R;
import com.basiclab.iot.device.constant.FunctionTypeConstant;
import com.basiclab.iot.device.constant.TdengineConstant;
import com.basiclab.iot.device.domain.device.vo.Device;
import com.basiclab.iot.device.domain.device.vo.Product;
import com.basiclab.iot.device.domain.device.vo.ProductProperties;
import com.basiclab.iot.device.domain.device.vo.TDDeviceDataResp;
import com.basiclab.iot.device.service.device.DeviceService;
import com.basiclab.iot.device.service.device.DeviceThingModelService;
import com.basiclab.iot.device.service.product.ProductPropertiesService;
import com.basiclab.iot.device.service.product.ProductService;
import com.basiclab.iot.tdengine.RemoteTdEngineService;
import com.basiclab.iot.tdengine.constant.SuperTableTypeConstant;
import com.basiclab.iot.tdengine.domain.DeviceData;
import com.basiclab.iot.tdengine.domain.query.TDDeviceDataRequest;
import org.apache.commons.compress.utils.Lists;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * DeviceThingModelServiceImpl
 *
 * @author 翱翔的雄库鲁
 * @email andywebjava@163.com
 * @wechat EasyAIoT2025
 */
@Service
public class DeviceThingModelServiceImpl implements DeviceThingModelService {

    @Resource
    private RemoteTdEngineService tdEngineService;
    @Resource
    private ProductPropertiesService productPropertiesService;
    @Resource
    private DeviceService deviceService;
    @Resource
    private ProductService productService;

    //设备详情-运行状态
    @Override
    public List<TDDeviceDataResp> getDeviceThingModels(Long id, String name) {
        //先查产品物模型
        Device device = deviceService.selectDeviceById(id);
        Product product = productService.selectByProductIdentification(device.getProductIdentification());
        ProductProperties productProperties = new ProductProperties();
        productProperties.setTemplateIdentification(product.getTemplateIdentification());
        productProperties.setProductIdentification(product.getProductIdentification());
        productProperties.setPropertyCode(name);
        productProperties.setPropertyName(name);
        List<ProductProperties> productPropertiesList = productPropertiesService.selectProductPropertiesList(productProperties);
        if (productPropertiesList.isEmpty()) {
            return Lists.newArrayList();
        }
        List<TDDeviceDataResp> result = BeanUtil.copyToList(productPropertiesList, TDDeviceDataResp.class);
        List<String> propertyCode = result.stream().map(TDDeviceDataResp::getPropertyCode).collect(Collectors.toList());
        // 从 TDengine 取最近一条属性上报（整包 params JSON）
        TDDeviceDataRequest request = new TDDeviceDataRequest();
        request.setDeviceIdentification(sanitizeTableSuffix(device.getDeviceIdentification()));
        request.setIdentifierList(propertyCode);
        request.setFunctionType(FunctionTypeConstant.PROPERTIES);
        request.setTdDatabaseName(TdengineConstant.IOT_DEVICE);
        request.setTdSuperTableName(SuperTableTypeConstant.PROPERTY_UPSTREAM_REPORT);
        List<DeviceData> deviceData = new ArrayList<>();
        R<List<DeviceData>> lastRowsListByIdentifier = tdEngineService.getLastRowsListByIdentifier(request);
        if (lastRowsListByIdentifier != null && lastRowsListByIdentifier.getData() != null) {
            deviceData = lastRowsListByIdentifier.getData();
        }
        if (!CollectionUtils.isEmpty(deviceData)) {
            DeviceData latest = deviceData.get(0);
            JSONObject params = parseParams(latest.getDataValue());
            long ts = latest.getLastUpdateTime();
            for (TDDeviceDataResp resp : result) {
                if (params != null && params.containsKey(resp.getPropertyCode())) {
                    Object value = params.get(resp.getPropertyCode());
                    resp.setDataValue(value == null ? null : String.valueOf(value));
                    resp.setTs(ts);
                } else if (params != null && params.size() == 1 && propertyCode.size() == 1) {
                    // 单属性时兼容直接上报标量/数组
                    Object value = params.values().iterator().next();
                    resp.setDataValue(value == null ? null : String.valueOf(value));
                    resp.setTs(ts);
                }
            }
            result.sort(Comparator.comparing(TDDeviceDataResp::getTs, Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
        }
        return result;
    }

    private JSONObject parseParams(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            Object parsed = JSON.parse(raw);
            if (parsed instanceof JSONObject) {
                return (JSONObject) parsed;
            }
            // 非对象时包一层，便于单值展示
            JSONObject wrap = new JSONObject();
            wrap.put("_value", parsed);
            return wrap;
        } catch (Exception e) {
            JSONObject wrap = new JSONObject();
            wrap.put("_raw", raw);
            return wrap;
        }
    }

    private String sanitizeTableSuffix(String deviceIdentification) {
        if (!StringUtils.hasText(deviceIdentification)) {
            return deviceIdentification;
        }
        String safeId = deviceIdentification.replaceAll("[^A-Za-z0-9_]", "_");
        if (!safeId.isEmpty() && !Character.isLetter(safeId.charAt(0)) && safeId.charAt(0) != '_') {
            safeId = "d_" + safeId;
        }
        return safeId;
    }

}
