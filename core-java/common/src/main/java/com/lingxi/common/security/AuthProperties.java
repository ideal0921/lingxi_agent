package com.lingxi.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 认证配置（M5-5.6）：密钥与有效期全部环境变量化；密钥须与 Go 网关 `GATEWAY_JWT_SECRET` 同值。
 */
@Component
@ConfigurationProperties(prefix = "lingxi.auth")
public class AuthProperties {

    /** 是否启用 REST 认证（生产必须为 true；false 仅用于本地免登录联调）。 */
    private boolean enabled = true;

    /** JWT 签名密钥（HS256，≥32 字节）。禁止硬编码：经环境变量 LINGXI_JWT_SECRET 注入。 */
    private String secret = "";

    /** 令牌有效期（秒），默认 8 小时。 */
    private long tokenTtlSeconds = 28800;

    /** 默认租户（登录未指定租户时的归属，保证本地单租户可用）。 */
    private String defaultTenant = "lingxi";

    /** 预置管理员登录名（首次启动幂等创建）。 */
    private String adminUsername = "admin";

    /** 预置管理员初始口令（环境变量 LINGXI_ADMIN_INIT_PASSWORD；生产必须覆盖默认值）。 */
    private String adminInitPassword = "";

    /** 预置管理员展示名。 */
    private String adminDisplayName = "平台管理员";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getTokenTtlSeconds() {
        return tokenTtlSeconds;
    }

    public void setTokenTtlSeconds(long tokenTtlSeconds) {
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    public String getDefaultTenant() {
        return defaultTenant;
    }

    public void setDefaultTenant(String defaultTenant) {
        this.defaultTenant = defaultTenant;
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public void setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername;
    }

    public String getAdminInitPassword() {
        return adminInitPassword;
    }

    public void setAdminInitPassword(String adminInitPassword) {
        this.adminInitPassword = adminInitPassword;
    }

    public String getAdminDisplayName() {
        return adminDisplayName;
    }

    public void setAdminDisplayName(String adminDisplayName) {
        this.adminDisplayName = adminDisplayName;
    }
}
