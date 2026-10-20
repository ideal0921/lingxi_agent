package com.lingxi.start.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.lingxi.common.security.AuthInterceptor;
import com.lingxi.common.security.AuthProperties;
import com.lingxi.trace.application.ApiTraceService;
import com.lingxi.trace.infrastructure.web.ApiTraceHttpInterceptor;

/**
 * REST 认证拦截器注册（M5-5.6）与 HTTP 链路 span 拦截器注册（M5-5.9）。
 *
 * <p>拦截范围：全部 `/api/v1/**`；豁免路径：健康检查、登录接口、Actuator（供 LB/K8s 探活）。
 * 认证开关关闭时（本地免登录联调）拦截器仍注册，但只注入开发态身份，不做校验。</p>
 */
@Configuration
public class WebAuthConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebAuthConfig.class);

    private final AuthInterceptor authInterceptor;
    private final ApiTraceService apiTraceService;
    private final AuthProperties authProperties;

    public WebAuthConfig(AuthInterceptor authInterceptor, ApiTraceService apiTraceService,
                         AuthProperties authProperties) {
        this.authInterceptor = authInterceptor;
        this.apiTraceService = apiTraceService;
        this.authProperties = authProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 豁免清单说明：登录必须豁免（否则无法取得令牌）；探活接口豁免以保证 K8s 探测可用。
        // 知识库 REST（/api/v1/kb/**）已于 M5-5.6 6.5 纳入认证范围（admin 限权，租户取上下文）。
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns(
                        "/api/v1/health",
                        "/api/v1/auth/login");
        // 链路 span 拦截器**必须注册在认证拦截器之后**：afterCompletion 按逆序执行，
        // 使其早于认证上下文清理，从而在记录 span 时仍能取到租户身份（health 探活排除，避免噪音）。
        registry.addInterceptor(new ApiTraceHttpInterceptor(apiTraceService, authProperties.getDefaultTenant()))
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/health");
        log.info("REST 认证拦截器已注册（enabled={}）", authInterceptor.isEnabled());
    }
}
