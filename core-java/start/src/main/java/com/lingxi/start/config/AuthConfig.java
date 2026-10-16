package com.lingxi.start.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.lingxi.common.security.AuthProperties;
import com.lingxi.common.security.AuthInterceptor;
import com.lingxi.common.security.JwtIssuer;
import com.lingxi.common.security.JwtVerifier;
import com.lingxi.common.security.RequestMetaFilter;

/**
 * 认证装配（M5-5.6）：口令编码器、JWT 签发/校验器与 REST 认证拦截器。
 *
 * <p>密钥与有效期来自 {@link AuthProperties}（环境变量），未配置密钥时启动即失败——
 * 宁可拒绝启动，也不允许出现「空密钥可伪造令牌」的运行态。</p>
 */
@Configuration
public class AuthConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthConfig.class);

    /** BCrypt 口令编码器：仅引入 spring-security-crypto，不启用完整安全过滤链。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtIssuer jwtIssuer(AuthProperties properties) {
        warnIfDefaultCredentials(properties);
        return new JwtIssuer(properties.getSecret(), properties.getTokenTtlSeconds());
    }

    /**
     * 示例密钥/默认口令检测（M5-5.7 安全加固）：命中即启动告警。
     *
     * <p>不直接拒绝启动是为保证本地开发可用；生产部署应将密钥与初始口令全部替换
     * （密钥随机 ≥32 字节、初始口令强且首次登录后修改）。</p>
     */
    private void warnIfDefaultCredentials(AuthProperties properties) {
        String secret = properties.getSecret() == null ? "" : properties.getSecret();
        String lowered = secret.toLowerCase();
        if (lowered.contains("change-me") || lowered.contains("dev-secret") || lowered.contains("example")) {
            log.warn("[security] 检测到疑似示例 JWT 密钥（LINGXI_JWT_SECRET），生产环境请立即替换为随机强密钥");
        }
        String adminPassword = properties.getAdminInitPassword();
        if (adminPassword != null && !adminPassword.isBlank() && adminPassword.length() < 12) {
            log.warn("[security] 内置管理员初始口令长度不足 12 位（LINGXI_ADMIN_INIT_PASSWORD），生产环境请覆盖并首次登录后修改");
        }
    }

    @Bean
    public JwtVerifier jwtVerifier(AuthProperties properties) {
        return new JwtVerifier(properties.getSecret());
    }

    @Bean
    public AuthInterceptor authInterceptor(AuthProperties properties, JwtVerifier jwtVerifier) {
        return new AuthInterceptor(properties, jwtVerifier);
    }

    /**
     * 请求元信息过滤器（最高优先级）：先于认证拦截器执行，保证登录等豁免路径也有来源信息，
     * 供操作日志审计使用。
     */
    @Bean
    public FilterRegistrationBean<RequestMetaFilter> requestMetaFilter() {
        FilterRegistrationBean<RequestMetaFilter> registration = new FilterRegistrationBean<>(new RequestMetaFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
