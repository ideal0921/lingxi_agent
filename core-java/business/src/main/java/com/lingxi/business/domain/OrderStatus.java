package com.lingxi.business.domain;

/**
 * 订单状态：与 {@code business.proto} 注释中的取值保持一致（PAID / SHIPPED / DELIVERED / REFUNDING / CLOSED）。
 *
 * <p>「是否允许发起退换货」的判定收敛到枚举自身，作为域内单一权威，
 * 避免各调用方各自散落地维护状态白名单。</p>
 */
public enum OrderStatus {
    PAID,
    SHIPPED,
    DELIVERED,
    REFUNDING,
    CLOSED;

    /** 仅「已支付且尚未进入退款/关闭流程」的订单可受理退换货申请。 */
    public boolean allowsReturn() {
        return this == PAID || this == SHIPPED || this == DELIVERED;
    }
}
