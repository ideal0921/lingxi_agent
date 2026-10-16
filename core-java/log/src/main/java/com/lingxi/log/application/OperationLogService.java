package com.lingxi.log.application;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lingxi.common.result.PageResult;
import com.lingxi.log.domain.OperationLog;
import com.lingxi.log.domain.repo.OperationLogRepository;

/**
 * 操作日志服务（M5-5.6）：写入（旁路，不阻断主链路）与分页查询。
 */
@Service
public class OperationLogService {

    private static final Logger log = LoggerFactory.getLogger(OperationLogService.class);

    /** 单页上限，防止管理后台一次拉取过大（同时避免恶意大 size 拖垮数据库）。 */
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final OperationLogRepository repository;

    public OperationLogService(OperationLogRepository repository) {
        this.repository = repository;
    }

    /**
     * 记录一条操作日志。
     *
     * <p>审计是旁路：写库失败仅告警，**绝不向调用方抛出**，避免审计故障影响业务主链路。</p>
     */
    public void record(OperationLogEntry entry) {
        if (entry == null) {
            return;
        }
        try {
            repository.append(new OperationLog(UUID.randomUUID().toString(),
                    entry.tenantId(), entry.userId(), entry.username(), entry.role(),
                    entry.module(), entry.action(), entry.resourceType(), entry.resourceId(),
                    entry.result(), entry.detail(), entry.clientIp(), entry.userAgent(),
                    entry.elapsedMs(), System.currentTimeMillis()));
        } catch (RuntimeException e) {
            log.warn("操作日志落库失败（不影响主链路）module={} action={} err={}",
                    entry.module(), entry.action(), e.getMessage());
        }
    }

    /** 分页查询：租户内过滤 + 模块/动作/结果/时间区间筛选。 */
    public PageResult<OperationLog> page(OperationLogQuery query, int page, int size) {
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        long total = repository.count(query);
        List<OperationLog> items = total == 0
                ? List.of()
                : repository.list(query, (normalizedPage - 1) * normalizedSize, normalizedSize);
        return PageResult.of(total, normalizedPage, normalizedSize, items);
    }
}
