package com.lingxi.start.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.lingxi.common.security.AuthInterceptor;

/**
 * REST 认证拦截器注册（M5-5.6）。
 *
 * <p>拦截范围：全部 `/api/v1/**`；豁免路径：健康检查、登录接口、Actuator（供 LB/K8s 探活）。
 * 认证开关关闭时（本地免登录联调）拦截器仍注册，但只注入开发态身份，不做校验。</p>
 */
@Configuration
public class WebAuthConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebAuthConfig.class);

    private final AuthInterceptor authInterceptor;

    public WebAuthConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
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
        log.info("REST 认证拦截器已注册（enabled={}）", authInterceptor.isEnabled());
    }
}
