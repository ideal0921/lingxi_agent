package com.lingxi.log.application;

import com.lingxi.common.security.RequestContext;
import com.lingxi.common.tenant.TenantContext;
import com.lingxi.common.tenant.TenantIdentity;
import com.lingxi.log.domain.OperationResult;

/**
 * 待落库的操作日志条目（M5-5.6）：业务侧只提供「模块/动作/资源/结果/说明/耗时」，
 * 主体与来源信息由 {@link #of} 从可信租户上下文与请求元信息自动补全，避免各处手工拼装出错。
 *
 * @param tenantId     租户标识（操作主体租户）
 * @param userId       操作主体用户 ID
 * @param username     操作主体登录名
 * @param role         操作主体角色
 * @param module       模块
 * @param action       动作
 * @param resourceType 资源类型（可为空）
 * @param resourceId   资源标识（可为空）
 * @param result       结果
 * @param detail       说明（不得包含口令等敏感信息）
 * @param clientIp     来源 IP
 * @param userAgent    来源 User-Agent
 * @param elapsedMs    耗时（毫秒）
 */
public record OperationLogEntry(String tenantId, String userId, String username, String role,
                                String module, String action, String resourceType, String resourceId,
                                String result, String detail, String clientIp, String userAgent,
                                long elapsedMs) {

    /**
     * 以当前请求上下文构造条目。
     *
     * <p>登录失败等「无身份」场景下可信上下文为空，此时由调用方显式指定 tenantId/username，
     * 见 {@link #ofAnonymous}。</p>
     */
    public static OperationLogEntry of(String tenantId, String userId, String username, String role,
                                       String module, String action, String resourceType, String resourceId,
                                       OperationResult result, String detail, long elapsedMs) {
        RequestContext.RequestMeta meta = RequestContext.current();
        return new OperationLogEntry(nullToEmpty(tenantId), nullToEmpty(userId), nullToEmpty(username),
                nullToEmpty(role), module, action, nullToEmpty(resourceType), nullToEmpty(resourceId),
                result.name(), nullToEmpty(detail), meta.clientIp(), meta.userAgent(), Math.max(elapsedMs, 0));
    }

    /** 以当前可信身份构造条目（常规路径：已认证后的管理动作）。 */
    public static OperationLogEntry ofCurrent(String module, String action, String resourceType,
                                              String resourceId, OperationResult result, String detail,
                                              long elapsedMs) {
        TenantIdentity identity = TenantContext.current();
        return of(identity.tenantId(), identity.userId(), "", identity.role(),
                module, action, resourceType, resourceId, result, detail, elapsedMs);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
