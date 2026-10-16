package com.lingxi.log.application;

import java.util.ArrayList;
import java.util.List;

import com.lingxi.log.domain.OperationLog;
import com.lingxi.log.domain.repo.OperationLogRepository;

/**
 * 记录型操作日志仓储替身（日志域测试用）：支持断言、筛选与故障注入。
 */
public final class RecordingLogRepository implements OperationLogRepository {

    private final List<OperationLog> logs = new ArrayList<>();
    /** 置为 true 时 append 抛异常，用于验证「审计失败不阻断主链路」。 */
    boolean failOnAppend;

    @Override
    public void append(OperationLog log) {
        if (failOnAppend) {
            throw new IllegalStateException("模拟落库失败");
        }
        logs.add(log);
    }

    @Override
    public List<OperationLog> list(OperationLogQuery query, int offset, int limit) {
        return filtered(query).stream().skip(offset).limit(limit).toList();
    }

    @Override
    public long count(OperationLogQuery query) {
        return filtered(query).size();
    }

    /** 记录到的日志（供断言；跨包测试亦需可读，故为 public）。 */
    public List<OperationLog> logs() {
        return logs;
    }

    private List<OperationLog> filtered(OperationLogQuery query) {
        return logs.stream()
                .filter(log -> log.tenantId().equals(query.tenantId()))
                .filter(log -> query.module() == null || query.module().equals(log.module()))
                .filter(log -> query.action() == null || query.action().equals(log.action()))
                .filter(log -> query.result() == null || query.result().equals(log.result()))
                .toList();
    }
}
