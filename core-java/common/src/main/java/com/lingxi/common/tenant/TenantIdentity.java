package com.lingxi.common.tenant;

/**
 * 租户身份（M5-5.5 租户隔离加固）：由接入层（Go 网关）解析 JWT 后经 gRPC metadata 下传，
 * 业务侧一律以本身份为准，**不再信任请求体/查询参数中的 tenant_id**。
 *
 * @param tenantId 租户标识（隔离主键，不可为空）
 * @param userId   用户标识（匿名接入时为空串）
 * @param role     主体角色：user / agent / admin（缺失为空串）
 */
public record TenantIdentity(String tenantId, String userId, String role) {

    /** 空身份：仅在无租户上下文的内部路径（如 Kafka 消费）中使用。 */
    public static final TenantIdentity NONE = new TenantIdentity("", "", "");

    public TenantIdentity {
        tenantId = tenantId == null ? "" : tenantId.trim();
        userId = userId == null ? "" : userId.trim();
        role = role == null ? "" : role.trim();
    }

    public boolean hasTenant() {
        return !tenantId.isEmpty();
    }

    /** 是否为指定租户（大小写敏感，租户 ID 为业务主键）。 */
    public boolean belongsTo(String otherTenantId) {
        return hasTenant() && tenantId.equals(otherTenantId);
    }
}
