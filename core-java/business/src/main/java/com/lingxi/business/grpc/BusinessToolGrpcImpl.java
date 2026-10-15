package com.lingxi.business.grpc;

import com.lingxi.business.application.BusinessToolService;
import com.lingxi.business.application.BusinessToolService.LogisticsResult;
import com.lingxi.business.application.BusinessToolService.OrderResult;
import com.lingxi.business.application.BusinessToolService.ReturnResult;
import com.lingxi.business.domain.BizLogistics;
import com.lingxi.business.domain.BizOrder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lingxi.business.v1.Business;
import lingxi.business.v1.BusinessToolServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 业务工具 gRPC 服务实现（lingxi.business.v1.BusinessToolService）：
 * 把 proto 请求映射到应用服务、把结果映射成 proto 应答。
 *
 * <p>本类只做协议适配，不含业务规则；未命中/拒绝属于常态应答（found/ok=false + reason），
 * 仅系统故障才回 {@link Status#INTERNAL}。</p>
 *
 * <p>由 {@code GrpcServerConfig} 注入全部 {@code BindableService} 自动注册到 gRPC 端口。</p>
 */
@Component
public class BusinessToolGrpcImpl extends BusinessToolServiceGrpc.BusinessToolServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(BusinessToolGrpcImpl.class);

    private final BusinessToolService service;

    public BusinessToolGrpcImpl(BusinessToolService service) {
        this.service = service;
    }

    @Override
    public void getOrder(Business.GetOrderRequest request,
                         StreamObserver<Business.OrderReply> observer) {
        try {
            OrderResult result = service.getOrder(request.getTenantId(), request.getOrderNo());
            observer.onNext(toOrderReply(result, request.getOrderNo()));
            observer.onCompleted();
        } catch (RuntimeException e) {
            log.error("订单查询失败 tenantId={} orderNo={}", request.getTenantId(), request.getOrderNo(), e);
            observer.onError(Status.INTERNAL.withDescription("订单查询失败").asRuntimeException());
        }
    }

    @Override
    public void getLogistics(Business.GetLogisticsRequest request,
                             StreamObserver<Business.LogisticsReply> observer) {
        try {
            LogisticsResult result = service.getLogistics(request.getTenantId(), request.getOrderNo());
            observer.onNext(toLogisticsReply(result, request.getOrderNo()));
            observer.onCompleted();
        } catch (RuntimeException e) {
            log.error("物流查询失败 tenantId={} orderNo={}", request.getTenantId(), request.getOrderNo(), e);
            observer.onError(Status.INTERNAL.withDescription("物流查询失败").asRuntimeException());
        }
    }

    @Override
    public void submitReturn(Business.SubmitReturnRequest request,
                             StreamObserver<Business.SubmitReturnReply> observer) {
        try {
            ReturnResult result = service.submitReturn(request.getTenantId(), request.getOrderNo(),
                    request.getReturnType(), request.getReason());
            observer.onNext(Business.SubmitReturnReply.newBuilder()
                    .setOk(result.ok())
                    .setReason(result.reason())
                    .setReturnId(result.returnId())
                    .setStatus(result.status().name())
                    .build());
            observer.onCompleted();
        } catch (RuntimeException e) {
            log.error("退换货受理失败 tenantId={} orderNo={}", request.getTenantId(), request.getOrderNo(), e);
            observer.onError(Status.INTERNAL.withDescription("退换货受理失败").asRuntimeException());
        }
    }

    /** 订单结果 → proto 应答：未命中时 order_no 回显请求值，便于上游上下文关联。 */
    private Business.OrderReply toOrderReply(OrderResult result, String requestedOrderNo) {
        Business.OrderReply.Builder builder = Business.OrderReply.newBuilder()
                .setFound(result.found())
                .setReason(result.reason());
        BizOrder order = result.order();
        if (order == null) {
            return builder.setOrderNo(requestedOrderNo).build();
        }
        return builder
                .setOrderNo(order.getOrderNo())
                .setStatus(order.getStatus().name())
                .setAmount(order.amountText())
                .setCreatedAtMs(order.getCreatedAtMs())
                .setItemsSummary(order.getItemsSummary())
                .build();
    }

    /** 物流结果 → proto 应答：未命中时 order_no 回显请求值。 */
    private Business.LogisticsReply toLogisticsReply(LogisticsResult result, String requestedOrderNo) {
        Business.LogisticsReply.Builder builder = Business.LogisticsReply.newBuilder()
                .setFound(result.found())
                .setReason(result.reason());
        BizLogistics logistics = result.logistics();
        if (logistics == null) {
            return builder.setOrderNo(requestedOrderNo).build();
        }
        return builder
                .setOrderNo(logistics.getOrderNo())
                .setCarrier(logistics.getCarrier())
                .setTrackingNo(logistics.getTrackingNo())
                .setStatus(logistics.getStatus().name())
                .setLatestEvent(logistics.getLatestEvent())
                .setUpdatedAtMs(logistics.getUpdatedAtMs())
                .build();
    }
}
