package com.lingxi.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import com.lingxi.common.exception.ForbiddenException;
import com.lingxi.common.result.Result;
import com.lingxi.common.security.AuthInterceptor;
import com.lingxi.common.security.AuthProperties;
import com.lingxi.common.security.JwtIssuer;
import com.lingxi.common.security.JwtVerifier;
import com.lingxi.common.tenant.MissingTenantContextException;
import com.lingxi.common.tenant.TenantContext;
import com.lingxi.common.tenant.TenantIdentity;
import com.lingxi.trace.api.dto.ApiTraceSpanView;
import com.lingxi.trace.application.ApiTraceBatchWriter;
import com.lingxi.trace.application.ApiTraceService;
import com.lingxi.trace.application.RecordingTraceRepository;
import com.lingxi.trace.domain.ApiTraceSpan;

/**
 * 链路查询接口单测（M5-5.9）：admin 限权、租户强过滤、分页上限、详情升序。
 */
class ApiTraceControllerTest {

    private static final String SECRET = "lingxi-test-secret-0123456789abcdef";

    private RecordingTraceRepository repository;
    private ApiTraceBatchWriter writer;
    private ApiTraceController controller;

    @BeforeEach
    void setUp() {
        repository = new RecordingTraceRepository();
        writer = new ApiTraceBatchWriter(repository, 200, 50, 1000);
        controller = new ApiTraceController(new ApiTraceService(repository, writer, true));
    }

    @AfterEach
    void tearDown() {
        writer.shutdown();
        TenantContext.clear();
    }

    @Test
    void nonAdminIsForbidden() throws Exception {
        AuthInterceptor auth = authInterceptor();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + new JwtIssuer(SECRET, 3600)
                .issue("tenant-1", "u-1", "bob", "user"));
        HandlerMethod handler = detailHandler();

        // @RequireRole(admin) 生效：普通用户访问链路查询应被拒
        assertThrows(ForbiddenException.class,
                () -> auth.preHandle(request, new MockHttpServletResponse(), handler));
    }

    @Test
    void adminIsAllowed() throws Exception {
        AuthInterceptor auth = authInterceptor();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + new JwtIssuer(SECRET, 3600)
                .issue("tenant-1", "u-1", "admin", "admin"));
        HandlerMethod handler = detailHandler();

        assertTrue(auth.preHandle(request, new MockHttpServletResponse(), handler));
        assertEquals("tenant-1", TenantContext.currentTenantId(), "认证后应注入租户上下文");
    }

    @Test
    void pageFiltersByTenant() {
        repository.appendBatch(List.of(
                span("s1", "trace-1", "tenant-1", "/api/v1/a", 1000L),
                span("s2", "trace-1", "tenant-1", "/api/v1/b", 2000L),
                span("s3", "trace-2", "tenant-2", "/api/v1/c", 3000L)));
        TenantContext.set(new TenantIdentity("tenant-1", "u-1", "admin"));

        Result<com.lingxi.common.result.PageResult<ApiTraceSpanView>> result =
                controller.page(null, null, null, null, null, 1, 20);

        assertEquals(2, result.data().total(), "只统计本租户 span");
    }

    @Test
    void pageClampsSizeToMax() {
        TenantContext.set(new TenantIdentity("tenant-1", "u-1", "admin"));

        Result<com.lingxi.common.result.PageResult<ApiTraceSpanView>> result =
                controller.page(null, null, null, null, null, 1, 9999);

        assertEquals(200, result.data().size(), "单页上限应限制为 200");
    }

    @Test
    void pageRequiresTenantContext() {
        // 缺少租户上下文（越过认证直连）应拒绝，而不是返回全量
        assertThrows(MissingTenantContextException.class,
                () -> controller.page(null, null, null, null, null, 1, 20));
    }

    @Test
    void detailFiltersCrossTenantAndSortsByStartedAt() {
        repository.appendBatch(List.of(
                span("s3", "trace-x", "tenant-1", "/api/v1/a", 3000L),
                span("s1", "trace-x", "tenant-1", "/api/v1/b", 1000L),
                span("s2", "trace-x", "tenant-1", "/api/v1/c", 2000L),
                span("other", "trace-x", "tenant-2", "/api/v1/d", 1500L)));
        TenantContext.set(new TenantIdentity("tenant-1", "u-1", "admin"));

        Result<List<ApiTraceSpanView>> result = controller.detail("trace-x");

        assertEquals(3, result.data().size(), "跨租户 span 应被过滤");
        assertEquals(List.of(1000L, 2000L, 3000L),
                result.data().stream().map(ApiTraceSpanView::startedAtMs).toList(),
                "应按 startedAtMs 升序");
        assertTrue(result.data().stream().allMatch(view -> "tenant-1".equals(view.tenantId())));
    }

    @Test
    void detailReturnsEmptyForOtherTenantTrace() {
        repository.appendBatch(List.of(span("s1", "trace-hidden", "tenant-2", "/api/v1/a", 1000L)));
        TenantContext.set(new TenantIdentity("tenant-1", "u-1", "admin"));

        Result<List<ApiTraceSpanView>> result = controller.detail("trace-hidden");

        assertEquals(List.of(), result.data(), "跨租户 traceId 应查不到");
    }

    /** 构造认证拦截器（与生产装配一致：启用认证 + HS256 密钥）。 */
    private AuthInterceptor authInterceptor() {
        AuthProperties properties = new AuthProperties();
        properties.setEnabled(true);
        properties.setSecret(SECRET);
        properties.setTokenTtlSeconds(3600);
        return new AuthInterceptor(properties, new JwtVerifier(SECRET));
    }

    private HandlerMethod detailHandler() throws NoSuchMethodException {
        return new HandlerMethod(controller, ApiTraceController.class.getDeclaredMethod("detail", String.class));
    }

    private static ApiTraceSpan span(String spanId, String traceId, String tenantId, String entry, long startedAtMs) {
        return new ApiTraceSpan(spanId, traceId, "", "core-java", "HTTP", entry, "GET",
                "OK", 200, tenantId, "u-1", "user", "127.0.0.1", 5, "", startedAtMs);
    }
}
