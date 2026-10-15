package com.lingxi.business.application;

import com.lingxi.business.application.BusinessToolService.LogisticsResult;
import com.lingxi.business.application.BusinessToolService.OrderResult;
import com.lingxi.business.application.BusinessToolService.ReturnResult;
import com.lingxi.business.domain.BizLogistics;
import com.lingxi.business.domain.BizOrder;
import com.lingxi.business.domain.BizReturnRequest;
import com.lingxi.business.domain.LogisticsStatus;
import com.lingxi.business.domain.OrderStatus;
import com.lingxi.business.domain.ReturnStatus;
import com.lingxi.business.domain.ReturnType;
import com.lingxi.business.domain.repo.LogisticsRepository;
import com.lingxi.business.domain.repo.OrderRepository;
import com.lingxi.business.domain.repo.ReturnRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务工具用例测试：租户隔离、订单/物流命中与未命中、退换货受理的成功与四类拒绝路径。
 *
 * <p>全部使用内存替身，不依赖 MySQL（与 knowledge 模块的用例测试风格一致）。</p>
 */
class BusinessToolServiceTest {

    private static final String TENANT = "t-ctx";
    private static final String OTHER_TENANT = "tenant-1";
    private static final String ORDER_NO = "LN20240815001";
    private static final long CREATED_AT = 1723680000000L;
    private static final long UPDATED_AT = 1723690800000L;

    private InMemoryOrderRepository orderRepo;
    private InMemoryLogisticsRepository logisticsRepo;
    private InMemoryReturnRequestRepository returnRepo;
    private BusinessToolService service;

    @BeforeEach
    void setUp() {
        orderRepo = new InMemoryOrderRepository();
        logisticsRepo = new InMemoryLogisticsRepository();
        returnRepo = new InMemoryReturnRequestRepository();
        service = new BusinessToolService(orderRepo, logisticsRepo, returnRepo);
    }

    // ---------------- 订单查询 ----------------

    @Test
    void getOrderHitsForOwnTenant() {
        orderRepo.save(order(ORDER_NO, TENANT, OrderStatus.DELIVERED, "199.00", "保温杯×1"));

        OrderResult result = service.getOrder(TENANT, ORDER_NO);

        assertTrue(result.found());
        assertEquals("", result.reason());
        assertEquals(ORDER_NO, result.order().getOrderNo());
        assertEquals("DELIVERED", result.order().getStatus().name());
        assertEquals("199.00", result.order().amountText());
        assertEquals(CREATED_AT, result.order().getCreatedAtMs());
        assertEquals("保温杯×1", result.order().getItemsSummary());
    }

    @Test
    void getOrderFormatsAmountWithTwoDecimals() {
        orderRepo.save(order("LN20240815002", TENANT, OrderStatus.SHIPPED, "88.5", "数据线×2、充电头×1"));

        OrderResult result = service.getOrder(TENANT, "LN20240815002");

        assertTrue(result.found());
        assertEquals("88.50", result.order().amountText(), "金额须固定两位小数");
    }

    @Test
    void getOrderMissesForOtherTenant() {
        orderRepo.save(order(ORDER_NO, TENANT, OrderStatus.DELIVERED, "199.00", "保温杯×1"));

        OrderResult result = service.getOrder(OTHER_TENANT, ORDER_NO);

        assertFalse(result.found(), "越权查询必须视为未命中");
        assertTrue(result.reason().contains("租户"), "未命中原因应说明租户归属");
    }

    @Test
    void getOrderMissesWhenOrderAbsent() {
        OrderResult result = service.getOrder(TENANT, "LN-UNKNOWN");

        assertFalse(result.found());
        assertFalse(result.reason().isBlank());
    }

    // ---------------- 物流查询 ----------------

    @Test
    void getLogisticsHitsForOwnTenant() {
        logisticsRepo.save(new BizLogistics("LN20240815002", TENANT, "顺丰速运", "SF1234567890",
                LogisticsStatus.IN_TRANSIT, "快件已到达杭州转运中心", UPDATED_AT));

        LogisticsResult result = service.getLogistics(TENANT, "LN20240815002");

        assertTrue(result.found());
        assertEquals("顺丰速运", result.logistics().getCarrier());
        assertEquals("SF1234567890", result.logistics().getTrackingNo());
        assertEquals("IN_TRANSIT", result.logistics().getStatus().name());
        assertEquals("快件已到达杭州转运中心", result.logistics().getLatestEvent());
        assertEquals(UPDATED_AT, result.logistics().getUpdatedAtMs());
    }

    @Test
    void getLogisticsMissesForOtherTenant() {
        logisticsRepo.save(new BizLogistics(ORDER_NO, TENANT, "顺丰速运", "SF1234567890",
                LogisticsStatus.IN_TRANSIT, "运输中", UPDATED_AT));

        LogisticsResult result = service.getLogistics(OTHER_TENANT, ORDER_NO);

        assertFalse(result.found(), "越权查询必须视为未命中");
        assertTrue(result.reason().contains("租户"));
    }

    @Test
    void getLogisticsMissesWhenNoRecord() {
        LogisticsResult result = service.getLogistics(TENANT, ORDER_NO);

        assertFalse(result.found());
        assertFalse(result.reason().isBlank());
    }

    // ---------------- 退换货受理 ----------------

    @Test
    void submitReturnAcceptPersistsAcceptedRequest() {
        orderRepo.save(order(ORDER_NO, TENANT, OrderStatus.PAID, "199.00", "保温杯×1"));

        ReturnResult result = service.submitReturn(TENANT, ORDER_NO, "REFUND", "  尺寸不合适  ");

        assertTrue(result.ok());
        assertEquals(ReturnStatus.ACCEPTED, result.status());
        assertFalse(result.returnId().isBlank(), "受理成功须返回受理单号");
        BizReturnRequest saved = returnRepo.findAcceptedByOrderNoAndTenant(ORDER_NO, TENANT).orElseThrow();
        assertEquals(result.returnId(), saved.getReturnId());
        assertEquals(ReturnType.REFUND, saved.getReturnType());
        assertEquals(ReturnStatus.ACCEPTED, saved.getStatus());
        assertEquals("尺寸不合适", saved.getReason(), "申请原因应去除首尾空白");
    }

    @Test
    void submitReturnAcceptsShippedAndDeliveredOrders() {
        orderRepo.save(order("N-SHIPPED", TENANT, OrderStatus.SHIPPED, "88.50", "数据线×2"));
        orderRepo.save(order("N-DELIVERED", TENANT, OrderStatus.DELIVERED, "59.90", "鼠标垫×1"));

        ReturnResult shipped = service.submitReturn(TENANT, "N-SHIPPED", "EXCHANGE", "有划痕");
        ReturnResult delivered = service.submitReturn(TENANT, "N-DELIVERED", "EXCHANGE", "有划痕");

        assertTrue(shipped.ok(), "SHIPPED 应可受理");
        assertTrue(delivered.ok(), "DELIVERED 应可受理");
        assertEquals(ReturnType.EXCHANGE,
                returnRepo.findAcceptedByOrderNoAndTenant("N-DELIVERED", TENANT).orElseThrow().getReturnType());
    }

    @Test
    void submitReturnRejectsUnknownOrder() {
        ReturnResult result = service.submitReturn(TENANT, "LN-UNKNOWN", "REFUND", "不喜欢");

        assertFalse(result.ok());
        assertEquals(ReturnStatus.REJECTED, result.status());
        assertTrue(result.reason().contains("订单"), "应提示订单不存在");
        assertEquals(0, returnRepo.count(), "拒绝的申请不应落库");
    }

    @Test
    void submitReturnRejectsOtherTenantOrder() {
        orderRepo.save(order(ORDER_NO, TENANT, OrderStatus.PAID, "199.00", "保温杯×1"));

        ReturnResult result = service.submitReturn(OTHER_TENANT, ORDER_NO, "REFUND", "不喜欢");

        assertFalse(result.ok(), "越权订单不可受理");
        assertEquals(0, returnRepo.count());
    }

    @Test
    void submitReturnRejectsRefundingOrder() {
        orderRepo.save(order(ORDER_NO, TENANT, OrderStatus.REFUNDING, "199.00", "保温杯×1"));

        ReturnResult result = service.submitReturn(TENANT, ORDER_NO, "REFUND", "重复申请");

        assertFalse(result.ok());
        assertTrue(result.reason().contains("退款"), "应说明订单处于退款流程中");
        assertEquals(0, returnRepo.count());
    }

    @Test
    void submitReturnRejectsClosedOrder() {
        orderRepo.save(order(ORDER_NO, TENANT, OrderStatus.CLOSED, "199.00", "保温杯×1"));

        ReturnResult result = service.submitReturn(TENANT, ORDER_NO, "REFUND", "后悔了");

        assertFalse(result.ok());
        assertTrue(result.reason().contains("关闭"), "应说明订单已关闭");
        assertEquals(0, returnRepo.count());
    }

    @Test
    void submitReturnRejectsDuplicateAcceptedRequest() {
        orderRepo.save(order(ORDER_NO, TENANT, OrderStatus.PAID, "199.00", "保温杯×1"));
        returnRepo.save(new BizReturnRequest(TENANT, ORDER_NO, ReturnType.REFUND, "首次申请"));

        ReturnResult result = service.submitReturn(TENANT, ORDER_NO, "REFUND", "再次申请");

        assertFalse(result.ok());
        assertTrue(result.reason().contains("重复"), "应提示已有在处理中的申请");
        assertEquals(1, returnRepo.count(), "既有申请不被覆盖，也不新增");
    }

    @Test
    void submitReturnRejectsInvalidReturnType() {
        orderRepo.save(order(ORDER_NO, TENANT, OrderStatus.PAID, "199.00", "保温杯×1"));

        ReturnResult result = service.submitReturn(TENANT, ORDER_NO, "COUPON", "非法类型");

        assertFalse(result.ok());
        assertTrue(result.reason().contains("REFUND"), "应说明合法取值");
        assertEquals(0, returnRepo.count());
    }

    // ---------------- 测试数据与内存替身 ----------------

    private static BizOrder order(String orderNo, String tenantId, OrderStatus status,
                                 String amount, String itemsSummary) {
        return new BizOrder(orderNo, tenantId, "u-1001", status, new BigDecimal(amount), itemsSummary, CREATED_AT);
    }

    private static final class InMemoryOrderRepository implements OrderRepository {
        private final Map<String, BizOrder> store = new HashMap<>();

        @Override
        public BizOrder save(BizOrder order) {
            store.put(order.getOrderNo(), order);
            return order;
        }

        @Override
        public Optional<BizOrder> findByOrderNoAndTenant(String orderNo, String tenantId) {
            return store.values().stream()
                    .filter(o -> o.getOrderNo().equals(orderNo) && o.getTenantId().equals(tenantId))
                    .findFirst();
        }
    }

    private static final class InMemoryLogisticsRepository implements LogisticsRepository {
        private final Map<String, BizLogistics> store = new HashMap<>();

        @Override
        public BizLogistics save(BizLogistics logistics) {
            store.put(logistics.getOrderNo(), logistics);
            return logistics;
        }

        @Override
        public Optional<BizLogistics> findByOrderNoAndTenant(String orderNo, String tenantId) {
            return store.values().stream()
                    .filter(l -> l.getOrderNo().equals(orderNo) && l.getTenantId().equals(tenantId))
                    .findFirst();
        }
    }

    private static final class InMemoryReturnRequestRepository implements ReturnRequestRepository {
        private final Map<String, BizReturnRequest> store = new HashMap<>();

        @Override
        public BizReturnRequest save(BizReturnRequest request) {
            store.put(request.getReturnId(), request);
            return request;
        }

        @Override
        public Optional<BizReturnRequest> findAcceptedByOrderNoAndTenant(String orderNo, String tenantId) {
            return store.values().stream()
                    .filter(r -> r.getOrderNo().equals(orderNo) && r.getTenantId().equals(tenantId))
                    .filter(r -> r.getStatus() == ReturnStatus.ACCEPTED)
                    .findFirst();
        }

        int count() {
            return store.size();
        }
    }
}
