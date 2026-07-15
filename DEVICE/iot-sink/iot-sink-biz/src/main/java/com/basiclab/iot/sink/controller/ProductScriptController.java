package com.basiclab.iot.sink.controller;

import cn.hutool.core.util.StrUtil;
import com.basiclab.iot.common.domain.R;
import com.basiclab.iot.common.utils.json.JsonUtils;
import com.basiclab.iot.sink.dal.dataobject.ProductScriptDO;
import com.basiclab.iot.sink.dal.mapper.ProductScriptMapper;
import com.basiclab.iot.sink.javascript.JsScriptManager;
import com.basiclab.iot.sink.messagebus.core.IotMessageBus;
import com.basiclab.iot.sink.service.product.ProductScriptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.script.ScriptException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 产品 JS 编解码脚本管理。
 * <p>
 * 标准 JSON（Alink/Topic Codec）可不配脚本；私有协议才需要配置 rawDataToProtocol / protocolToRawData。
 */
@Tag(name = "产品协议脚本")
@RestController
@RequestMapping("/product-script")
@RequiredArgsConstructor
@Slf4j
public class ProductScriptController {

    private final ProductScriptService productScriptService;
    private final ProductScriptMapper productScriptMapper;
    private final JsScriptManager jsScriptManager;

    @Resource
    @Lazy
    private IotMessageBus messageBus;

    private static final String SCRIPT_CHANGE_TOPIC = "iot_product_script_change";

    @Operation(summary = "按产品标识查询脚本（含未启用）")
    @GetMapping("/{productIdentification}")
    public R<ProductScriptDO> get(@PathVariable String productIdentification) {
        return R.ok(productScriptMapper.selectByProductIdentification(productIdentification));
    }

    @Operation(summary = "保存或更新脚本，并热加载到引擎")
    @PostMapping
    public R<?> save(@RequestBody ProductScriptDO script) throws ScriptException {
        if (script == null || StrUtil.isBlank(script.getProductIdentification())) {
            return R.fail("productIdentification 不能为空");
        }
        if (StrUtil.isNotBlank(script.getScriptContent())) {
            JsScriptManager.CheckResult checkResult = jsScriptManager.checkScript(script.getScriptContent());
            if (!checkResult.isSuccess()) {
                return R.fail("脚本校验失败: " + checkResult.getMessage());
            }
        }

        ProductScriptDO existing = productScriptMapper.selectByProductIdentification(script.getProductIdentification());
        if (existing != null) {
            script.setId(existing.getId());
            if (script.getProductId() == null) {
                script.setProductId(existing.getProductId());
            }
            script.setScriptVersion(existing.getScriptVersion() == null ? 1 : existing.getScriptVersion() + 1);
        } else if (script.getScriptVersion() == null) {
            script.setScriptVersion(1);
        }
        if (script.getCreateTime() == null) {
            script.setCreateTime(LocalDateTime.now());
        }
        script.setUpdateTime(LocalDateTime.now());
        productScriptService.saveOrUpdate(script);

        if (Boolean.TRUE.equals(script.getScriptEnabled()) && StrUtil.isNotBlank(script.getScriptContent())) {
            jsScriptManager.addScript(script.getProductIdentification(), script.getScriptContent());
        } else {
            jsScriptManager.removeScript(script.getProductIdentification());
        }
        publishChange(script.getProductId(), script.getProductIdentification(), "update");
        return R.ok(true);
    }

    @Operation(summary = "删除脚本")
    @DeleteMapping("/{productId}")
    public R<Boolean> delete(@PathVariable Long productId,
                             @RequestParam(required = false) String productIdentification) {
        productScriptService.deleteByProductId(productId);
        if (StrUtil.isNotBlank(productIdentification)) {
            jsScriptManager.removeScript(productIdentification);
        }
        publishChange(productId, productIdentification, "delete");
        return R.ok(true);
    }

    @Operation(summary = "校验脚本语法")
    @PostMapping("/check")
    public R<?> check(@RequestBody Map<String, String> body) {
        String content = body != null ? body.get("scriptContent") : null;
        if (StrUtil.isBlank(content)) {
            return R.fail("scriptContent 不能为空");
        }
        JsScriptManager.CheckResult checkResult = jsScriptManager.checkScript(content);
        if (!checkResult.isSuccess()) {
            return R.fail("脚本校验失败: " + checkResult.getMessage());
        }
        return R.ok(true);
    }

    private void publishChange(Long productId, String productIdentification, String action) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("productId", productId);
            payload.put("productIdentification", productIdentification);
            payload.put("action", action);
            messageBus.post(SCRIPT_CHANGE_TOPIC, JsonUtils.toJsonString(payload));
        } catch (Exception e) {
            log.warn("[publishChange][脚本变更消息发送失败: {}]", e.getMessage());
        }
    }
}
