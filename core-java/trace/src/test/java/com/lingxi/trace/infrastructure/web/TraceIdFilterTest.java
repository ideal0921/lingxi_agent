package com.lingxi.trace.infrastructure.web;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.lingxi.common.trace.TraceContext;
import com.lingxi.common.trace.TraceIds;

/**
 * 链路 ID 过滤器单测（M5-5.9）：入站复用/缺省生成、响应头回写、以及线程复用不串号。
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    void reusesInboundTraceIdAndWritesResponseHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIds.HEADER_TRACE_ID, "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seenInChain.set(TraceContext.traceId()));

        assertEquals("abc-123", seenInChain.get(), "入站带 x-trace-id 应复用");
        assertEquals("abc-123", response.getHeader(TraceIds.RESPONSE_HEADER_TRACE_ID), "响应头应回写 traceId");
    }

    @Test
    void generatesTraceIdWhenAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seenInChain.set(TraceContext.traceId()));

        assertFalse(seenInChain.get().isBlank(), "无入站 traceId 时应生成新的");
        assertEquals(seenInChain.get(), response.getHeader(TraceIds.RESPONSE_HEADER_TRACE_ID));
    }

    @Test
    void clearsContextBetweenRequestsOnReusedThread() throws Exception {
        // 模拟容器线程第一次请求
        MockHttpServletRequest first = new MockHttpServletRequest();
        first.addHeader(TraceIds.HEADER_TRACE_ID, "trace-a");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, (req, res) -> {
        });

        assertFalse(TraceContext.hasTrace(), "请求结束后必须清理，防线程复用串号");

        // 同一线程处理第二次请求：不得残留上一次的 traceId
        MockHttpServletRequest second = new MockHttpServletRequest();
        second.addHeader(TraceIds.HEADER_TRACE_ID, "trace-b");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, (req, res) -> {
        });

        assertEquals("trace-b", secondResponse.getHeader(TraceIds.RESPONSE_HEADER_TRACE_ID));
        assertEquals("", TraceContext.traceId(), "第二次请求后同样必须清理");
    }
}
