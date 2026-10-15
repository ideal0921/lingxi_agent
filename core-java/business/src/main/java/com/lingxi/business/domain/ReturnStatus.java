package com.lingxi.business.domain;

/**
 * 退换货申请状态。
 *
 * <p>{@link #ACCEPTED} 表示已受理（在 {@code biz_return_request} 落库，视为「未完结」申请，
 * 同订单不可重复提交）；{@link #REJECTED} 为未受理的对外应答状态，不落库。</p>
 */
public enum ReturnStatus {
    ACCEPTED,
    REJECTED
}
