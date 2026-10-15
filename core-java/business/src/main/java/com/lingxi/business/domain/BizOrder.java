package com.lingxi.business.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 业务订单聚合：AI 域经 Function Calling 查询的订单视图（只读）。
 *
 * <p><b>金额</b>：以 {@link BigDecimal} 承载（对应库表 {@code DECIMAL(10,2)}），
 * 对外一律用 {@link #amountText()} 输出两位小数字符串，避免 double 的浮点误差。</p>
 *
 * <p><b>租户隔离</b>：{@code tenantId} 为检索的必要条件，仓储按
 * (order_no, tenant_id) 双键查询，越权订单一律表现为「未命中」。</p>
 */
public class BizOrder {

    private static final int MAX_TEXT_LEN = 500;

    private final String orderNo;
    private final String tenantId;
    private final String userId;
    private final OrderStatus status;
    private final BigDecimal amount;
    private final String itemsSummary;
    private final long createdAtMs;

    /** 新建订单（数据同步/测试造数；创建时间取当前时刻）。 */
    public BizOrder(String orderNo, String tenantId, String userId, OrderStatus status,
                    BigDecimal amount, String itemsSummary) {
        this(orderNo, tenantId, userId, status, amount, itemsSummary, System.currentTimeMillis());
    }

    /** 仅供仓储从持久化数据恢复。 */
    public BizOrder(String orderNo, String tenantId, String userId, OrderStatus status,
                    BigDecimal amount, String itemsSummary, long createdAtMs) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("订单号不能为空");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("租户 ID 不能为空");
        }
        if (status == null) {
            throw new IllegalArgumentException("订单状态不能为空");
        }
        if (amount == null) {
            throw new IllegalArgumentException("订单金额不能为空");
        }
        this.orderNo = orderNo;
        this.tenantId = tenantId;
        this.userId = userId == null ? "" : userId;
        this.status = status;
        this.amount = amount.setScale(2, RoundingMode.HALF_UP);
        this.itemsSummary = itemsSummary == null ? "" : truncate(itemsSummary);
        this.createdAtMs = createdAtMs;
    }

    /** 是否允许发起退换货（状态白名单由 {@link OrderStatus} 单一维护）。 */
    public boolean allowsReturn() {
        return status.allowsReturn();
    }

    /** 金额对外文本：固定两位小数（如 199.00 / 88.50）。 */
    public String amountText() {
        return amount.toPlainString();
    }

    private static String truncate(String text) {
        return text.length() > MAX_TEXT_LEN ? text.substring(0, MAX_TEXT_LEN) : text;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getItemsSummary() {
        return itemsSummary;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }
}
