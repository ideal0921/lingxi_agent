package com.lingxi.trace.infrastructure.web;

import org.springframework.web.servlet.HandlerInterceptor;

import com.lingxi.common.trace.TraceContext;
import com.lingxi.trace.application.ApiTraceEntry;
import com.lingxi.trace.application.ApiTraceService;
import com.lingxi.trace.domain.ApiTraceSpan;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * HTTP 链路 span 拦截器（M5-5.9）：preHandle 记开始时间，afterCompletion 记 span 并旁路落库。
 *
 * <p>关键：本拦截器**必须注册在认证拦截器之后**。拦截器的 afterCompletion 按注册逆序执行，
 * 故本拦截器先于认证拦截器清理 {@link com.lingxi.common.tenant.TenantContext}，从而能取到租户身份。</p>
 */
public class ApiTraceHttpInterceptor implements HandlerInterceptor {

    /** 开始时间存放于请求属性（前缀化避免与业务参数冲突）。 */
    private static final String ATTR_STARTED_AT = "lingxi.trace.startedAt";

    private final ApiTraceService service;
    /** 默认租户：供未认证请求（登录等）的 span 归属回落，避免因租户为空在管理端不可见。 */
    private final String fallbackTenantId;

    public ApiTraceHttpInterceptor(ApiTraceService service, String fallbackTenantId) {
        this.service = service;
        this.fallbackTenantId = fallbackTenantId == null ? "" : fallbackTenantId;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(ATTR_STARTED_AT, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Object started = request.getAttribute(ATTR_STARTED_AT);
        if (!(started instanceof Long startedAt)) {
            // 本拦截器 preHandle 未执行（如前序认证拦截器在 preHandle 抛异常）时无 span 可记
            return;
        }
        int statusCode = response.getStatus();
        String status = (ex != null || statusCode >= 400) ? ApiTraceSpan.STATUS_ERROR : ApiTraceSpan.STATUS_OK;
        service.record(ApiTraceEntry.ofHttp(
                TraceContext.traceId(), TraceContext.spanId(), request.getRequestURI(), request.getMethod(),
                status, statusCode, System.currentTimeMillis() - startedAt,
                ex == null ? "" : ex.getMessage(), startedAt, fallbackTenantId));
    }
}
