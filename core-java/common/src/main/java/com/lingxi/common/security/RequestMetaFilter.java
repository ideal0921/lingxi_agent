package com.lingxi.common.security;

import java.io.IOException;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 请求元信息过滤器（M5-5.6）：无论是否经过认证，都采集来源 IP 与 User-Agent 供操作日志使用。
 *
 * <p>为什么独立于认证拦截器：登录接口豁免认证（否则无法取得令牌），若只在认证流程里采集，
 * 登录审计将缺失来源信息；过滤器对所有请求生效，保证审计字段完整。</p>
 */
public class RequestMetaFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        RequestContext.set(clientIp(request), request.getHeader("User-Agent"));
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 线程可能被容器复用：必须清理，避免来源信息串号
            RequestContext.clear();
        }
    }

    /** 取真实来源 IP：网关透传 X-Forwarded-For 优先，其次直连地址。 */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr() == null ? "" : request.getRemoteAddr();
    }
}
