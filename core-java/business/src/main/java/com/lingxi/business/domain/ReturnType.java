package com.lingxi.business.domain;

/**
 * 退换货类型：仅支持 {@link #REFUND}（仅退款）与 {@link #EXCHANGE}（换货），
 * 与 {@code business.proto} 中 {@code return_type} 的取值一致。
 */
public enum ReturnType {
    REFUND,
    EXCHANGE
}
