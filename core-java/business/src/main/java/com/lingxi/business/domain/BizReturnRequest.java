package com.lingxi.business.domain;

import java.util.UUID;

/**
 * 退换货受理单聚合：受理成功即为已受理（{@link ReturnStatus#ACCEPTED}）并落库。
 *
 * <p>受理单号由领域内生成（UUID），应用层只负责校验订单归属与可受理状态，
 * 保证「受理单合法性」这一不变量始终由聚合成对维护。</p>
 */
public class BizReturnRequest {

    private static final int MAX_REASON_LEN = 500;

    private final String returnId;
    private final String tenantId;
    private final String orderNo;
    private final ReturnType returnType;
    private final String reason;
    private final ReturnStatus status;
    private final long createdAtMs;

    /** 新建受理单：生成受理单号，状态固定为已受理。 */
    public BizReturnRequest(String tenantId, String orderNo, ReturnType returnType, String reason) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("租户 ID 不能为空");
        }
        if (orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("订单号不能为空");
        }
        if (returnType == null) {
            throw new IllegalArgumentException("退换货类型不能为空");
        }
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.length() > MAX_REASON_LEN) {
            throw new IllegalArgumentException("申请原因超过上限 " + MAX_REASON_LEN);
        }
        this.returnId = UUID.randomUUID().toString();
        this.tenantId = tenantId;
        this.orderNo = orderNo;
        this.returnType = returnType;
        this.reason = trimmed;
        this.status = ReturnStatus.ACCEPTED;
        this.createdAtMs = System.currentTimeMillis();
    }

    /** 仅供仓储从持久化数据恢复（跳过默认初始化，状态/时间由存储回填）。 */
    public BizReturnRequest(String returnId, String tenantId, String orderNo, ReturnType returnType,
                            String reason, ReturnStatus status, long createdAtMs) {
        this.returnId = returnId;
        this.tenantId = tenantId;
        this.orderNo = orderNo;
        this.returnType = returnType;
        this.reason = reason;
        this.status = status;
        this.createdAtMs = createdAtMs;
    }

    public String getReturnId() {
        return returnId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public ReturnType getReturnType() {
        return returnType;
    }

    public String getReason() {
        return reason;
    }

    public ReturnStatus getStatus() {
        return status;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }
}
