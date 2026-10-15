package com.lingxi.business.domain;

/**
 * 物流状态：与 {@code business.proto} 注释中的取值保持一致
 * （PENDING / PICKED / IN_TRANSIT / OUT_FOR_DELIVERY / DELIVERED / EXCEPTION）。
 *
 * <p>物流状态由承运商侧回传，业务工具域只读不改，故无状态机迁移规则。</p>
 */
public enum LogisticsStatus {
    PENDING,
    PICKED,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED,
    EXCEPTION
}
