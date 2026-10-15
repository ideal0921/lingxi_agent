package com.lingxi.report.grpc;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.lingxi.report.application.ReportQueryService;
import com.lingxi.report.domain.CsatSnapshot;
import com.lingxi.report.domain.FeedbackItem;
import com.lingxi.report.domain.FunnelStageCount;
import com.lingxi.report.domain.TokenReport;
import com.lingxi.report.domain.TokenUsageRow;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lingxi.report.v1.Report;
import lingxi.report.v1.ReportServiceGrpc;

/**
 * 报表域 gRPC 服务实现（M5-5.1）：网关通过此接口取会话漏斗 / Token 消耗 / 满意度报表。
 *
 * <p>纯查询接口，无写路径；租户由请求携带并**强制**下发到仓储过滤（前端不可绕过）。</p>
 */
@Component
public class ReportServiceGrpcImpl extends ReportServiceGrpc.ReportServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(ReportServiceGrpcImpl.class);

    private final ReportQueryService queryService;

    public ReportServiceGrpcImpl(ReportQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public void getFunnelReport(Report.ReportRange request,
                                StreamObserver<Report.FunnelReport> observer) {
        if (request.getTenantId().isBlank()) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("tenant_id 不能为空").asRuntimeException());
            return;
        }
        try {
            List<FunnelStageCount> stages = queryService.funnel(request.getTenantId(),
                    request.getFromMs(), request.getToMs());
            Report.FunnelReport.Builder builder = Report.FunnelReport.newBuilder();
            int created = 0;
            for (FunnelStageCount stage : stages) {
                if ("created".equals(stage.stageWire())) {
                    created = stage.sessions();
                }
                builder.addStages(Report.FunnelStageCount.newBuilder()
                        .setStage(stage.stageWire())
                        .setLabel(stage.label())
                        .setSessions(stage.sessions())
                        .setStepRate(stage.stepRate())
                        .setTotalRate(stage.totalRate())
                        .build());
            }
            builder.setCreatedSessions(created);
            observer.onNext(builder.build());
            observer.onCompleted();
        } catch (RuntimeException e) {
            log.error("查询会话漏斗失败 tenantId={}", request.getTenantId(), e);
            observer.onError(Status.INTERNAL.withDescription("查询会话漏斗失败").asRuntimeException());
        }
    }

    @Override
    public void getTokenReport(Report.ReportRange request,
                               StreamObserver<Report.TokenReport> observer) {
        if (request.getTenantId().isBlank()) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("tenant_id 不能为空").asRuntimeException());
            return;
        }
        try {
            TokenReport report = queryService.tokens(request.getTenantId(), request.getFromMs(),
                    request.getToMs());
            Report.TokenReport.Builder builder = Report.TokenReport.newBuilder()
                    .setTotalRequests(report.totalRequests())
                    .setTotalPromptTokens(report.totalPromptTokens())
                    .setTotalCompletionTokens(report.totalCompletionTokens());
            for (TokenUsageRow row : report.rows()) {
                builder.addRows(Report.TokenUsageRow.newBuilder()
                        .setStatDate(row.statDate())
                        .setProvider(row.provider())
                        .setModel(row.model())
                        .setRequests(row.requests())
                        .setPromptTokens(row.promptTokens())
                        .setCompletionTokens(row.completionTokens())
                        .setEstimatedRequests(row.estimatedRequests())
                        .setFirstTokenMsAvg(row.firstTokenMsAvg())
                        .build());
            }
            observer.onNext(builder.build());
            observer.onCompleted();
        } catch (RuntimeException e) {
            log.error("查询 Token 报表失败 tenantId={}", request.getTenantId(), e);
            observer.onError(Status.INTERNAL.withDescription("查询 Token 报表失败").asRuntimeException());
        }
    }

    @Override
    public void getCsatReport(Report.ReportRange request,
                              StreamObserver<Report.CsatReport> observer) {
        if (request.getTenantId().isBlank()) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("tenant_id 不能为空").asRuntimeException());
            return;
        }
        try {
            CsatSnapshot snapshot = queryService.csat(request.getTenantId(), request.getFromMs(),
                    request.getToMs());
            Report.CsatReport.Builder builder = Report.CsatReport.newBuilder()
                    .setFeedbackCount(snapshot.feedbackCount())
                    .setAverageRating(snapshot.averageRating())
                    .setCsat(snapshot.csat());
            for (Map.Entry<Integer, Integer> entry : snapshot.distribution().entrySet()) {
                builder.addDistribution(Report.RatingBucket.newBuilder()
                        .setRating(entry.getKey())
                        .setCount(entry.getValue())
                        .build());
            }
            for (FeedbackItem item : snapshot.recent()) {
                builder.addRecent(Report.FeedbackItem.newBuilder()
                        .setSessionId(item.sessionId())
                        .setRating(item.rating())
                        .setComment(item.comment())
                        .setCreatedAtMs(item.createdAtMs())
                        .build());
            }
            observer.onNext(builder.build());
            observer.onCompleted();
        } catch (RuntimeException e) {
            log.error("查询满意度报表失败 tenantId={}", request.getTenantId(), e);
            observer.onError(Status.INTERNAL.withDescription("查询满意度报表失败").asRuntimeException());
        }
    }
}
