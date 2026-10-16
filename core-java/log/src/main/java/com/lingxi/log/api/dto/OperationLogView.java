package com.lingxi.log.api.dto;

import com.lingxi.log.domain.OperationLog;

/**
 * 操作日志 JSON 视图（M5-5.6）：管理后台日志页展示字段。
 */
public record OperationLogView(String logId, String tenantId, String userId, String username, String role,
                               String module, String action, String resourceType, String resourceId,
                               String result, String detail, String clientIp, String userAgent,
                               long elapsedMs, long createdAtMs) {

    /** 领域日志 → 视图。 */
    public static OperationLogView from(OperationLog log) {
        return new OperationLogView(log.logId(), log.tenantId(), log.userId(), log.username(), log.role(),
                log.module(), log.action(), log.resourceType(), log.resourceId(),
                log.result(), log.detail(), log.clientIp(), log.userAgent(),
                log.elapsedMs(), log.createdAtMs());
    }
}
