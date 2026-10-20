package com.lingxi.trace.application;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.lingxi.trace.domain.ApiTraceSpan;
import com.lingxi.trace.domain.repo.ApiTraceRepository;

/**
 * 记录型链路仓储替身（trace 域测试用）：支持断言、筛选与故障注入。
 *
 * <p>并发安全：批量写入器是后台线程，测试线程读取断言，故用 {@link CopyOnWriteArrayList}。</p>
 */
public final class RecordingTraceRepository implements ApiTraceRepository {

    private final List<ApiTraceSpan> spans = new CopyOnWriteArrayList<>();
    /** 置为 true 时 appendBatch 抛异常，用于验证「写库失败不抛出、不影响调用方」。 */
    boolean failOnAppend;

    @Override
    public void appendBatch(List<ApiTraceSpan> batch) {
        if (failOnAppend) {
            throw new IllegalStateException("模拟落库失败");
        }
        if (batch != null) {
            spans.addAll(batch);
        }
    }

    @Override
    public long count(ApiTraceQuery query) {
        return filtered(query).size();
    }

    @Override
    public List<ApiTraceSpan> list(ApiTraceQuery query, int offset, int limit) {
        return filtered(query).stream().skip(offset).limit(limit).toList();
    }

    @Override
    public List<ApiTraceSpan> listByTraceId(String traceId) {
        return spans.stream().filter(span -> span.traceId().equals(traceId)).toList();
    }

    /** 记录到的 span（供断言）。 */
    public List<ApiTraceSpan> spans() {
        return spans;
    }

    private List<ApiTraceSpan> filtered(ApiTraceQuery query) {
        return spans.stream()
                .filter(span -> span.tenantId().equals(query.tenantId()))
                .filter(span -> query.traceId() == null || query.traceId().equals(span.traceId()))
                .filter(span -> query.entry() == null || query.entry().equals(span.entry()))
                .filter(span -> query.status() == null || query.status().equals(span.status()))
                .toList();
    }
}
