package com.lingxi.business.domain.repo;

import com.lingxi.business.domain.BizLogistics;

import java.util.Optional;

/**
 * 物流仓储端口：查询一律以 (order_no, tenant_id) 双键检索，实现强制租户隔离。
 */
public interface LogisticsRepository {

    BizLogistics save(BizLogistics logistics);

    /** 按订单号 + 租户查询最新物流轨迹（未命中返回 empty）。 */
    Optional<BizLogistics> findByOrderNoAndTenant(String orderNo, String tenantId);
}
