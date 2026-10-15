package com.lingxi.business.domain;

/**
 * 物流轨迹聚合：某订单的最新承运信息（只读）。
 *
 * <p>{@code latestEvent} 为可直接入提示词的自然语言动态；{@code tenantId} 与订单同源，
 * 仓储按 (order_no, tenant_id) 双键查询以实现强制租户隔离。</p>
 */
public class BizLogistics {

    private static final int MAX_TEXT_LEN = 500;

    private final String orderNo;
    private final String tenantId;
    private final String carrier;
    private final String trackingNo;
    private final LogisticsStatus status;
    private final String latestEvent;
    private final long updatedAtMs;

    /** 新建物流记录（数据同步/测试造数；更新时间取当前时刻）。 */
    public BizLogistics(String orderNo, String tenantId, String carrier, String trackingNo,
                        LogisticsStatus status, String latestEvent) {
        this(orderNo, tenantId, carrier, trackingNo, status, latestEvent, System.currentTimeMillis());
    }

    /** 仅供仓储从持久化数据恢复。 */
    public BizLogistics(String orderNo, String tenantId, String carrier, String trackingNo,
                        LogisticsStatus status, String latestEvent, long updatedAtMs) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("订单号不能为空");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("租户 ID 不能为空");
        }
        if (status == null) {
            throw new IllegalArgumentException("物流状态不能为空");
        }
        this.orderNo = orderNo;
        this.tenantId = tenantId;
        this.carrier = carrier == null ? "" : carrier;
        this.trackingNo = trackingNo == null ? "" : trackingNo;
        this.status = status;
        this.latestEvent = latestEvent == null ? "" : truncate(latestEvent);
        this.updatedAtMs = updatedAtMs;
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

    public String getCarrier() {
        return carrier;
    }

    public String getTrackingNo() {
        return trackingNo;
    }

    public LogisticsStatus getStatus() {
        return status;
    }

    public String getLatestEvent() {
        return latestEvent;
    }

    public long getUpdatedAtMs() {
        return updatedAtMs;
    }
}
