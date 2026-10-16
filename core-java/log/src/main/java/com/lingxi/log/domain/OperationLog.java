package com.lingxi.log.domain;

/**
 * 操作日志（M5-5.6）：只追加、不更新，作为「谁在何时对哪个租户做了什么」的审计事实。
 *
 * @param logId       日志 ID（UUID，主键，重放不重复）
 * @param tenantId    租户标识
 * @param userId      操作主体用户 ID（登录失败时为空）
 * @param username    操作主体登录名
 * @param role        操作主体角色
 * @param module      模块：auth / user / security / knowledge / workflow / session / ticket / report
 * @param action      动作：login / logout / create / update / delete / isolation_denied …
 * @param resourceType 目标资源类型（可为空）
 * @param resourceId  目标资源标识（可为空）
 * @param result      结果：SUCCESS / FAILED / DENIED
 * @param detail      结果说明（失败原因；**不得包含口令等敏感信息**）
 * @param clientIp    来源 IP
 * @param userAgent   来源 User-Agent
 * @param elapsedMs   耗时（毫秒）
 * @param createdAtMs 记录时间（epoch ms）
 */
public record OperationLog(String logId, String tenantId, String userId, String username, String role,
                           String module, String action, String resourceType, String resourceId,
                           String result, String detail, String clientIp, String userAgent,
                           long elapsedMs, long createdAtMs) {
}
