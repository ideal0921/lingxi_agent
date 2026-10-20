package com.lingxi.trace.domain.repo;

import java.util.List;

import com.lingxi.trace.application.ApiTraceQuery;
import com.lingxi.trace.domain.ApiTraceSpan;

/**
 * 链路 Span 存储抽象（M5-5.9）：默认 MySQL 实现，内存实现供单元测试。
 *
 * <p>写入只有 {@link #appendBatch} 一条批量入口——落库一律旁路，由批量写入器异步调用。</p>
 */
public interface ApiTraceRepository {

    /** 批量追加 span（只插入、不更新）。 */
    void appendBatch(List<ApiTraceSpan> spans);

    /** 分页查询命中总数：租户内过滤，支持 traceId/入口/结果/时间区间。 */
    long count(ApiTraceQuery query);

    /** 分页查询：租户内过滤 + 筛选条件。 */
    List<ApiTraceSpan> list(ApiTraceQuery query, int offset, int limit);

    /** 查某条链路的全部 span（按 started_at 升序，用于时间线）。 */
    List<ApiTraceSpan> listByTraceId(String traceId);
}
