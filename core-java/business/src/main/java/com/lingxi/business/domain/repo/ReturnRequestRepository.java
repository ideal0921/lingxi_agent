package com.lingxi.business.domain.repo;

import com.lingxi.business.domain.BizReturnRequest;

import java.util.Optional;

/**
 * 退换货受理单仓储端口。
 */
public interface ReturnRequestRepository {

    BizReturnRequest save(BizReturnRequest request);

    /** 查询该租户该订单下已受理（未完结）的申请，用于重复提交拦截。 */
    Optional<BizReturnRequest> findAcceptedByOrderNoAndTenant(String orderNo, String tenantId);
}
