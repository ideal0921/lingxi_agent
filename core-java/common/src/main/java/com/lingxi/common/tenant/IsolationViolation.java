package com.lingxi.common.tenant;

/**
 * 数据隔离越权事件（M5-5.5 数据隔离审计）：跨租户访问被拒时的审计事实。
 *
 * @param tenantId         访问方租户（当前上下文租户）
 * @param principal        访问主体（用户/坐席 ID，可为空）
 * @param role             主体角色（user/agent/admin，可为空）
 * @param resourceType     资源类型（session/ticket/workflow/...）
 * @param resourceId       资源标识（会话 ID、工单 ID 等）
 * @param resourceTenantId 资源实际归属租户（与访问方不一致即为越权）
 * @param action           访问动作（read/send_message/take_over/update_status/...）
 * @param detail           补充说明（拒绝原因等）
 */
public record IsolationViolation(String tenantId, String principal, String role,
                                 String resourceType, String resourceId, String resourceTenantId,
                                 String action, String detail) {
}
