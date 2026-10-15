package com.lingxi.business.application;

import com.lingxi.business.domain.BizLogistics;
import com.lingxi.business.domain.BizOrder;
import com.lingxi.business.domain.BizReturnRequest;
import com.lingxi.business.domain.OrderStatus;
import com.lingxi.business.domain.ReturnStatus;
import com.lingxi.business.domain.ReturnType;
import com.lingxi.business.domain.repo.LogisticsRepository;
import com.lingxi.business.domain.repo.OrderRepository;
import com.lingxi.business.domain.repo.ReturnRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 业务工具用例编排（M4-4.2）：订单查询、物流查询、退换货受理。
 *
 * <p><b>强制租户隔离</b>：全部用例以 (tenantId, orderNo) 为检索键；「不存在」与
 * 「不属于当前租户」对外统一返回未命中原因（同一话术），避免跨租户信息泄露。</p>
 *
 * <p><b>结果约定</b>：查询/受理失败属于常态业务分支（AI 域据此降级话术），
 * 因此以结果对象返回 found/ok + reason，而非抛异常——异常仅保留给真正的系统故障。</p>
 */
@Service
public class BusinessToolService {

    private static final Logger log = LoggerFactory.getLogger(BusinessToolService.class);

    /** 未命中原因统一话术：不区分「订单不存在」与「不属于本租户」，防止越权探测。 */
    private static final String REASON_ORDER_MISS = "订单不存在或不属于当前租户";
    private static final String REASON_LOGISTICS_MISS = "物流信息不存在或不属于当前租户";
    private static final String REASON_INVALID_RETURN_TYPE =
            "退换货类型不合法，仅支持 REFUND（仅退款）/ EXCHANGE（换货）";
    private static final String REASON_DUPLICATE_RETURN = "该订单已有在处理中的退换货申请，请勿重复提交";
    private static final int MAX_RETURN_REASON_LEN = 500;

    private final OrderRepository orderRepository;
    private final LogisticsRepository logisticsRepository;
    private final ReturnRequestRepository returnRequestRepository;

    public BusinessToolService(OrderRepository orderRepository,
                               LogisticsRepository logisticsRepository,
                               ReturnRequestRepository returnRequestRepository) {
        this.orderRepository = orderRepository;
        this.logisticsRepository = logisticsRepository;
        this.returnRequestRepository = returnRequestRepository;
    }

    /** 查询订单：命中返回订单视图，未命中/越权返回 found=false + 原因。 */
    public OrderResult getOrder(String tenantId, String orderNo) {
        if (isBlank(tenantId) || isBlank(orderNo)) {
            return OrderResult.miss(REASON_ORDER_MISS);
        }
        return orderRepository.findByOrderNoAndTenant(orderNo, tenantId)
                .map(OrderResult::hit)
                .orElseGet(() -> OrderResult.miss(REASON_ORDER_MISS));
    }

    /** 查询物流：命中返回最新轨迹，未命中/越权返回 found=false + 原因。 */
    public LogisticsResult getLogistics(String tenantId, String orderNo) {
        if (isBlank(tenantId) || isBlank(orderNo)) {
            return LogisticsResult.miss(REASON_LOGISTICS_MISS);
        }
        return logisticsRepository.findByOrderNoAndTenant(orderNo, tenantId)
                .map(LogisticsResult::hit)
                .orElseGet(() -> LogisticsResult.miss(REASON_LOGISTICS_MISS));
    }

    /**
     * 提交退换货申请：订单归属、可受理状态、重复申请、类型合法性四项校验通过后，
     * 生成受理单（ACCEPTED）并落库。
     */
    public ReturnResult submitReturn(String tenantId, String orderNo, String returnTypeRaw, String reason) {
        ReturnType returnType = parseReturnType(returnTypeRaw);
        if (returnType == null) {
            return ReturnResult.reject(REASON_INVALID_RETURN_TYPE);
        }
        if (isBlank(tenantId) || isBlank(orderNo)) {
            return ReturnResult.reject(REASON_ORDER_MISS);
        }
        BizOrder order = orderRepository.findByOrderNoAndTenant(orderNo, tenantId).orElse(null);
        if (order == null) {
            return ReturnResult.reject(REASON_ORDER_MISS);
        }
        if (!order.allowsReturn()) {
            return ReturnResult.reject(returnNotAllowedReason(order.getStatus()));
        }
        if (returnRequestRepository.findAcceptedByOrderNoAndTenant(orderNo, tenantId).isPresent()) {
            return ReturnResult.reject(REASON_DUPLICATE_RETURN);
        }
        BizReturnRequest accepted = returnRequestRepository.save(
                new BizReturnRequest(tenantId, orderNo, returnType, truncateReason(reason)));
        log.info("受理退换货申请 returnId={} orderNo={} tenantId={} returnType={}",
                accepted.getReturnId(), orderNo, tenantId, returnType);
        return ReturnResult.accepted(accepted.getReturnId());
    }

    /** 不可受理状态的拒绝原因（区分退款中与已关闭，便于 AI 域给出差异化话术）。 */
    private static String returnNotAllowedReason(OrderStatus status) {
        return switch (status) {
            case REFUNDING -> "订单正在退款处理中，无法重复申请退换货";
            case CLOSED -> "订单已关闭，无法申请退换货";
            default -> "订单当前状态不支持退换货：" + status;
        };
    }

    /** 解析退换货类型，非法值返回 null（由调用方转为拒绝应答）。 */
    private static ReturnType parseReturnType(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toUpperCase()) {
            case "REFUND" -> ReturnType.REFUND;
            case "EXCHANGE" -> ReturnType.EXCHANGE;
            default -> null;
        };
    }

    /** 截断超长申请原因：AI 生成文本可能过长，此处兜底保证入库不因长度失败。 */
    private static String truncateReason(String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        return trimmed.length() > MAX_RETURN_REASON_LEN ? trimmed.substring(0, MAX_RETURN_REASON_LEN) : trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 订单查询结果：未命中时 {@code order} 为 null、{@code reason} 为非空话术。 */
    public record OrderResult(boolean found, String reason, BizOrder order) {

        static OrderResult hit(BizOrder order) {
            return new OrderResult(true, "", order);
        }

        static OrderResult miss(String reason) {
            return new OrderResult(false, reason, null);
        }
    }

    /** 物流查询结果：未命中时 {@code logistics} 为 null、{@code reason} 为非空话术。 */
    public record LogisticsResult(boolean found, String reason, BizLogistics logistics) {

        static LogisticsResult hit(BizLogistics logistics) {
            return new LogisticsResult(true, "", logistics);
        }

        static LogisticsResult miss(String reason) {
            return new LogisticsResult(false, reason, null);
        }
    }

    /** 退换货受理结果：未受理时 {@code returnId} 为空、状态为 {@link ReturnStatus#REJECTED}。 */
    public record ReturnResult(boolean ok, String reason, String returnId, ReturnStatus status) {

        static ReturnResult accepted(String returnId) {
            return new ReturnResult(true, "", returnId, ReturnStatus.ACCEPTED);
        }

        static ReturnResult reject(String reason) {
            return new ReturnResult(false, reason, "", ReturnStatus.REJECTED);
        }
    }
}
