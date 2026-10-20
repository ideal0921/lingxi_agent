package com.lingxi.trace.application;

import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.lingxi.common.result.PageResult;
import com.lingxi.trace.domain.ApiTraceSpan;
import com.lingxi.trace.domain.repo.ApiTraceRepository;

/**
 * 链路 Span 服务（M5-5.9）：采集入队（旁路）+ 分页查询 + 链路详情。
 *
 * <p>开关策略：{@code lingxi.trace.enabled=false} 时在 {@link #record} 处**早退**（不落库），
 * 而查询接口不受影响（无数据自然为空）。选择「服务内早退」而非条件装配，是因为
 * TraceIdFilter/拦截器仍需照常注册以完成 traceId 透传与响应头回写，只有落库需要被关闭。</p>
 */
@Service
public class ApiTraceService {

    private static final Logger log = LoggerFactory.getLogger(ApiTraceService.class);

    /** 单页上限，防止管理后台一次拉取过大（同时避免恶意大 size 拖垮数据库）。 */
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 20;
    /** 错误摘要列上限（与表 error_msg VARCHAR(512) 对齐）。 */
    private static final int ERROR_MSG_MAX_LEN = 512;

    private final ApiTraceRepository repository;
    private final ApiTraceBatchWriter batchWriter;
    private final boolean enabled;

    public ApiTraceService(ApiTraceRepository repository, ApiTraceBatchWriter batchWriter,
                           @Value("${lingxi.trace.enabled:true}") boolean enabled) {
        this.repository = repository;
        this.batchWriter = batchWriter;
        this.enabled = enabled;
    }

    /** 采集是否启用（供启动日志确认）。 */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 记录一条 span（旁路）。
     *
     * <p>只入队、异步批量落库：入队异常仅告警，**绝不向调用方抛出**，
     * 避免 trace 故障影响业务主链路与首字延迟。</p>
     */
    public void record(ApiTraceEntry entry) {
        if (!enabled || entry == null) {
            return;
        }
        try {
            batchWriter.record(toSpan(entry));
        } catch (RuntimeException e) {
            log.warn("链路 span 入队失败（不影响主链路）component={} entry={} err={}",
                    entry.component(), entry.entry(), e.getMessage());
        }
    }

    /** 分页查询：租户内过滤 + traceId/入口/结果/时间区间筛选，单页上限 200。 */
    public PageResult<ApiTraceSpan> page(ApiTraceQuery query, int page, int size) {
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        long total = repository.count(query);
        List<ApiTraceSpan> items = total == 0
                ? List.of()
                : repository.list(query, (normalizedPage - 1) * normalizedSize, normalizedSize);
        return PageResult.of(total, normalizedPage, normalizedSize, items);
    }

    /**
     * 某条链路的全部 span：**强制租户过滤**（跨租户统一视为查不到），按 startedAtMs 升序用于时间线。
     *
     * <p>仓储端口按契约只按 traceId 取数（不跨租户下传 tenant），故租户过滤在服务层兜底，
     * 保证不因实现差异而泄露跨租户数据。</p>
     */
    public List<ApiTraceSpan> traceDetail(String tenantId, String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return List.of();
        }
        return repository.listByTraceId(traceId).stream()
                .filter(span -> span.tenantId().equals(tenantId))
                .sorted(Comparator.comparingLong(ApiTraceSpan::startedAtMs))
                .toList();
    }

    /** 条目 → 领域 span：补固定 service、截断错误摘要。 */
    private ApiTraceSpan toSpan(ApiTraceEntry entry) {
        return new ApiTraceSpan(entry.spanId(), entry.traceId(), entry.parentSpanId(), ApiTraceSpan.SERVICE,
                entry.component(), entry.entry(), entry.httpMethod(), entry.status(), entry.statusCode(),
                entry.tenantId(), entry.userId(), entry.role(), entry.clientIp(), entry.elapsedMs(),
                truncate(entry.errorMsg()), entry.startedAtMs());
    }

    /** 错误摘要截断到列上限，避免超长异常信息写库失败。 */
    private static String truncate(String errorMsg) {
        String value = errorMsg == null ? "" : errorMsg;
        return value.length() <= ERROR_MSG_MAX_LEN ? value : value.substring(0, ERROR_MSG_MAX_LEN);
    }
}
