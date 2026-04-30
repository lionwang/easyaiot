package com.basiclab.iot.video.sdk.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/**
 * 海康开放平台认证参数（用于批量获取摄像头）。
 */
public class HikPlatformAuthRequest {

    @NotBlank
    @Pattern(regexp = "^(?i)(http|https)$", message = "protocol 仅支持 http 或 https")
    private String protocol;

    @NotBlank
    private String host;

    @NotBlank
    private String appKey;

    @NotBlank
    private String appSecret;

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }
}
