package com.lingxi.business.domain.repo;

import com.lingxi.business.domain.BizOrder;

import java.util.Optional;

/**
 * 订单仓储端口。
 *
 * <p>查询一律以 (order_no, tenant_id) 双键检索：越权订单在存储层即被过滤，
 * 不存在的订单与不属于当前租户的订单对外表现一致，杜绝跨租户探测。</p>
 */
public interface OrderRepository {

    BizOrder save(BizOrder order);

    /** 按订单号 + 租户查询（未命中返回 empty）。 */
    Optional<BizOrder> findByOrderNoAndTenant(String orderNo, String tenantId);
}
