package com.jitsimeet.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Jitsi JWT 签发配置，密钥只保留在服务端，避免前端产物泄露 app_secret。
 */
@ConfigurationProperties(prefix = "jitsi-token")
public class JitsiTokenProperties {
    private String appId;
    private String appSecret;
    private String domain;
    private long ttlHours;
    private String roleCheckSecret;

    public String getRoleCheckSecret() { return roleCheckSecret; }
    public void setRoleCheckSecret(String value) { roleCheckSecret = value; }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public long getTtlHours() {
        return ttlHours;
    }

    public void setTtlHours(long ttlHours) {
        this.ttlHours = ttlHours;
    }
}
