package com.lingxi.common.security;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.lingxi.common.exception.ForbiddenException;
import com.lingxi.common.exception.UnauthorizedException;
import com.lingxi.common.tenant.TenantContext;
import com.lingxi.common.tenant.TenantIdentity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * REST 认证与授权拦截器（M5-5.6）。
 *
 * <p>职责：①从 `Authorization: Bearer` 或查询参数 `token` 解析令牌（后者供浏览器 WSS 握手使用）；
 * ②校验签名与时效；③注入 {@link TenantContext} 供业务侧取租户/用户/角色；
 * ④校验 {@link RequireRole} 注解；⑤记录来源元信息供操作日志使用；⑥请求结束清理上下文。</p>
 *
 * <p>细粒度校验放在 Java 侧的意义：即使有人绕过网关直连业务端口，也无法越权。</p>
 */
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    /** 与 Go 网关一致：优先请求头，其次查询参数（WSS 场景）。 */
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String PARAM_TOKEN = "token";
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthProperties properties;
    private final JwtVerifier verifier;

    public AuthInterceptor(AuthProperties properties, JwtVerifier verifier) {
        this.properties = properties;
        this.verifier = verifier;
    }

    /** 认证是否启用（供启动日志与配置校验使用）。 */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 认证关闭时（仅本地免登录联调）注入开发态身份：租户取 `X-Tenant-Id` 头或配置默认租户，
     * 角色取 `X-Role` 头（缺省 admin），使用户/日志等管理接口在本地可用。
     * 该分支在 `lingxi.auth.enabled=true`（生产）下不可达。
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.isEnabled()) {
            String tenant = headerOrDefault(request, "X-Tenant-Id", properties.getDefaultTenant());
            String role = headerOrDefault(request, "X-Role", JwtClaims.ROLE_ADMIN);
            TenantContext.set(new TenantIdentity(tenant, "dev-user", role));
            return true;
        }
        String token = resolveToken(request);
        if (token.isBlank()) {
            throw new UnauthorizedException("未登录或登录已过期");
        }
        JwtClaims claims = verifier.verify(token);
        TenantContext.set(new TenantIdentity(claims.tenantId(), claims.userId(), claims.role()));
        checkRole(handler, claims);
        return true;
    }

    /** 读取请求头，空白时回落给定默认值。 */
    private String headerOrDefault(HttpServletRequest request, String header, String fallback) {
        String value = request.getHeader(header);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 线程可能被复用：无论成功失败都必须清理，避免身份串号
        // （来源元信息由 RequestMetaFilter 负责清理）
        TenantContext.clear();
    }

    /** 校验 {@link RequireRole}：未标注仅要求已认证；admin 恒放行。 */
    private void checkRole(Object handler, JwtClaims claims) {
        RequireRole require = resolveRequireRole(handler);
        if (require == null || require.value().length == 0) {
            return;
        }
        if (claims.isAdmin()) {
            return;
        }
        List<String> allowed = Arrays.asList(require.value());
        if (!allowed.contains(claims.role())) {
            log.warn("[auth-denied] 角色不足已拒绝 user={} role={} required={}",
                    claims.userId(), claims.role(), allowed);
            throw new ForbiddenException("当前账号无权限访问该功能");
        }
    }

    /** 方法级注解优先，其次类级注解。 */
    private RequireRole resolveRequireRole(Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return null;
        }
        RequireRole onMethod = method.getMethodAnnotation(RequireRole.class);
        return onMethod != null ? onMethod : method.getBeanType().getAnnotation(RequireRole.class);
    }

    /**
     * 解析令牌：优先 `Authorization: Bearer`，其次查询参数 `token`（WSS 握手场景）。
     *
     * <p>注意：查询参数**刻意不调用** {@code request.getParameter()}——该调用会触发 Servlet 容器
     * 对请求体的参数解析，对 multipart 上传（知识库文档/分片）会导致 400 Bad Request；
     * 故此处直接解析原始查询串，完全不触碰请求体。</p>
     */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER_AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return tokenFromQuery(request);
    }

    /** 从原始查询串提取 token（URL 解码；不触发请求体解析）。 */
    private String tokenFromQuery(HttpServletRequest request) {
        String query = request.getQueryString();
        if (query == null || query.isBlank()) {
            return "";
        }
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            if (separator <= 0 || !PARAM_TOKEN.equals(pair.substring(0, separator))) {
                continue;
            }
            try {
                return URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8).trim();
            } catch (IllegalArgumentException e) {
                return "";
            }
        }
        return "";
    }
}
