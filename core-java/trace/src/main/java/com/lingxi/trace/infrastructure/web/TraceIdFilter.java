package com.lingxi.trace.infrastructure.web;

import java.io.IOException;

import org.springframework.web.filter.OncePerRequestFilter;

import com.lingxi.common.trace.TraceContext;
import com.lingxi.common.trace.TraceIds;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 链路 ID 过滤器（M5-5.9）HTTP 接入层：绑定 traceId、回写响应头、用毕清理。
 *
 * <p>放在最外层（最高优先级）的原因：只有最外层才能保证后续的元信息采集、认证拦截器、
 * 业务处理都在同一链路上下文中执行；也保证登录等豁免路径同样纳入链路。</p>
 *
 * <p>仅负责「绑定/回写/清理」，**不落 span**——span 的记录放在认证之后的 HandlerInterceptor，
 * 以便 afterCompletion 阶段仍能取到租户身份。</p>
 */
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 入站已带 x-trace-id 则复用（便于跨端联调/外部系统传入），否则生成新链路
        String traceId = TraceIds.normalize(request.getHeader(TraceIds.HEADER_TRACE_ID));
        // 当前线程的 span 即本次 HTTP 调用的 span，供下游（如 Kafka 生产）挂父子关系
        TraceContext.set(traceId, TraceIds.newSpanId());
        // 回写响应头：前端与排障可直接取用该 traceId 去管理端检索
        response.setHeader(TraceIds.RESPONSE_HEADER_TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 线程可能被容器复用：必须清理，防止链路串号
            TraceContext.clear();
        }
    }
}
