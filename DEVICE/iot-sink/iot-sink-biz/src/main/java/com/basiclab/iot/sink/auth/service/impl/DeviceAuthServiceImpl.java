package com.basiclab.iot.sink.auth.service.impl;

import cn.hutool.core.lang.Assert;
import cn.hutool.json.JSONObject;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import cn.hutool.core.util.StrUtil;
import com.basiclab.iot.common.utils.date.LocalDateTimeUtils;
import com.basiclab.iot.sink.auth.service.DeviceAuthService;
import com.basiclab.iot.sink.biz.dto.IotDeviceAuthReqDTO;
import com.basiclab.iot.sink.config.IotGatewayProperties;
import com.basiclab.iot.sink.dal.dataobject.DeviceDO;
import com.basiclab.iot.sink.service.device.DeviceService;
import com.basiclab.iot.sink.util.IotDeviceAuthUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static com.basiclab.iot.common.exception.util.ServiceExceptionUtil.exception;
import static com.basiclab.iot.sink.enums.ErrorCodeConstants.DEVICE_TOKEN_EXPIRED;

/**
 * DeviceAuthServiceImpl
 *
 * @author 翱翔的雄库鲁
 * @email andywebjava@163.com
 * @wechat EasyAIoT2025
 */

@Service
@Slf4j
public class DeviceAuthServiceImpl implements DeviceAuthService {

    @Resource
    private DeviceService deviceService;

    @Resource
    private IotGatewayProperties gatewayProperties;

    @Override
    public boolean authDevice(IotDeviceAuthReqDTO authReqDTO) {
        return authenticateDevice(authReqDTO) != null;
    }

    @Override
    public DeviceDO authenticateDevice(IotDeviceAuthReqDTO authReqDTO) {
        Assert.notNull(authReqDTO, "认证请求不能为空");
        Assert.notBlank(authReqDTO.getClientId(), "客户端 ID 不能为空");
        Assert.notBlank(authReqDTO.getUsername(), "用户名不能为空");

        // 解析用户名获取产品标识和设备名称
        IotDeviceAuthUtils.DeviceInfo deviceInfo = parseUsername(authReqDTO.getUsername());
        if (deviceInfo == null) {
            log.warn("[authDevice][解析设备信息失败，username: {}]", authReqDTO.getUsername());
            return null;
        }

        String protocolType = StrUtil.blankToDefault(authReqDTO.getProtocolType(), "MQTT").toUpperCase();
        DeviceDO device = deviceService.getDeviceForProtocolAuth(
                authReqDTO.getClientId(),
                deviceInfo.getProductIdentification(),
                deviceInfo.getDeviceIdentification(),
                "ENABLE",
                protocolType
        );

        if (device == null) {
            log.warn("[authDevice][设备认证失败，clientId: {}, username: {}]", 
                    authReqDTO.getClientId(), authReqDTO.getUsername());
            return null;
        }

        String authMode = normalizeAuthMode(device.getAuthMode());
        boolean accountPasswordValid = verifyAccountPassword(authReqDTO, device);
        boolean keyPairValid = verifyKeyPair(authReqDTO, device);
        boolean authenticated = switch (authMode) {
            case "KEY_PAIR" -> keyPairValid;
            case "ACCOUNT_OR_KEY_PAIR" -> accountPasswordValid || keyPairValid;
            default -> accountPasswordValid;
        };
        if (!authenticated) {
            log.warn("[authDevice][认证凭据无效，设备 ID: {}, authMode: {}]", device.getId(), authMode);
            return null;
        }

        log.info("[authDevice][设备认证成功，设备 ID: {}, 设备唯一标识: {}]", 
                device.getId(), device.getDeviceIdentification());
        return device;
    }

    private boolean verifyAccountPassword(IotDeviceAuthReqDTO request, DeviceDO device) {
        if (StrUtil.isBlank(request.getPassword())
                || !Objects.equals(device.getPassword(), request.getPassword())) {
            return false;
        }
        // MQTT：username 为 device&product 身份串，密码用产品凭据校验；
        // TCP 等协议可额外传 account，再与产品 userName 比对。
        if (StrUtil.isBlank(request.getAccount()) && parseUsername(request.getUsername()) != null) {
            return true;
        }
        String account = StrUtil.blankToDefault(request.getAccount(), request.getUsername());
        return Objects.equals(device.getUserName(), account);
    }

    private boolean verifyKeyPair(IotDeviceAuthReqDTO request, DeviceDO device) {
        if (StrUtil.isBlank(request.getSignature()) || request.getTimestamp() == null
                || StrUtil.isBlank(device.getPublicKey())) {
            return false;
        }
        if (Math.abs(System.currentTimeMillis() - request.getTimestamp()) > 300_000L) {
            log.warn("[verifyKeyPair][签名时间戳已过期，clientId: {}]", request.getClientId());
            return false;
        }
        try {
            String publicKeyBody = device.getPublicKey()
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            var publicKey = KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBody)));
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            String content = request.getClientId() + "|" + request.getUsername() + "|" + request.getTimestamp();
            verifier.update(content.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(request.getSignature()));
        } catch (Exception e) {
            log.warn("[verifyKeyPair][RSA 签名校验失败，clientId: {}]", request.getClientId(), e);
            return false;
        }
    }

    private String normalizeAuthMode(String value) {
        if (StrUtil.isBlank(value)) {
            return "ACCOUNT_PASSWORD";
        }
        return switch (value.trim().toUpperCase()) {
            case "PASSWORD", "密码", "账号/密码" -> "ACCOUNT_PASSWORD";
            case "PUBLIC_PRIVATE_KEY", "公/私钥" -> "KEY_PAIR";
            case "账号/密码+公/私钥" -> "ACCOUNT_OR_KEY_PAIR";
            default -> value.trim().toUpperCase();
        };
    }

    @Override
    public String createToken(String productIdentification, String deviceIdentification) {
        Assert.notBlank(productIdentification, "productIdentification 不能为空");
        Assert.notBlank(deviceIdentification, "deviceIdentification 不能为空");
        // 构建 JWT payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("productIdentification", productIdentification);
        payload.put("deviceIdentification", deviceIdentification);
        LocalDateTime expireTime = LocalDateTimeUtils.addTime(gatewayProperties.getToken().getExpiration());
        payload.put("exp", LocalDateTimeUtils.toEpochSecond(expireTime)); // 过期时间（exp 是 JWT 规范推荐）

        // 生成 JWT Token
        return JWTUtil.createToken(payload, gatewayProperties.getToken().getSecret().getBytes());
    }

    @Override
    public IotDeviceAuthUtils.DeviceInfo verifyToken(String token) {
        Assert.notBlank(token, "token 不能为空");
        // 校验 JWT Token
        boolean verify = JWTUtil.verify(token, gatewayProperties.getToken().getSecret().getBytes());
        if (!verify) {
            throw exception(DEVICE_TOKEN_EXPIRED);
        }

        // 解析 Token
        JWT jwt = JWTUtil.parseToken(token);
        JSONObject payload = jwt.getPayloads();
        // 检查过期时间
        Long exp = payload.getLong("exp");
        if (exp == null || exp < System.currentTimeMillis() / 1000) {
            throw exception(DEVICE_TOKEN_EXPIRED);
        }
        // 向后兼容：优先使用新字段，如果没有则使用旧字段
        String productIdentification = payload.getStr("productIdentification");
        String deviceIdentification = payload.getStr("deviceIdentification");
        Assert.notBlank(productIdentification, "productIdentification 不能为空");
        Assert.notBlank(deviceIdentification, "deviceIdentification 不能为空");
        return new IotDeviceAuthUtils.DeviceInfo().setProductIdentification(productIdentification).setDeviceIdentification(deviceIdentification);
    }

    @Override
    public IotDeviceAuthUtils.DeviceInfo parseUsername(String username) {
        return IotDeviceAuthUtils.parseUsername(username);
    }
}

