package com.lingxi.workflow.grpc;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.lingxi.common.exception.BizException;
import com.lingxi.workflow.application.WorkflowService;
import com.lingxi.workflow.domain.Workflow;
import com.lingxi.workflow.domain.WorkflowVersion;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lingxi.workflow.v1.WorkflowOuterClass;
import lingxi.workflow.v1.WorkflowServiceGrpc;

/**
 * 编排域 gRPC 服务实现：网关通过此接口完成工作流 CRUD、草稿保存、版本发布与回滚。
 *
 * <p>响应形态约定：带 {@code ok}/{@code reason} 字段的接口以业务结果返回拒绝原因；
 * 列表/详情类接口（无 ok 字段）在失败时以 gRPC 状态码上报。
 * 参数非法 → INVALID_ARGUMENT；资源不存在 → NOT_FOUND；其余 → INTERNAL。</p>
 */
@Component
public class WorkflowServiceGrpcImpl extends WorkflowServiceGrpc.WorkflowServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(WorkflowServiceGrpcImpl.class);

    /** 与 WorkflowService 中的资源不存在错误码保持一致。 */
    private static final int ERR_WORKFLOW_NOT_FOUND = 40402;
    private static final int ERR_VERSION_NOT_FOUND = 40403;

    private final WorkflowService workflowService;

    public WorkflowServiceGrpcImpl(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @Override
    public void listWorkflows(WorkflowOuterClass.ListWorkflowsRequest request,
                              StreamObserver<WorkflowOuterClass.ListWorkflowsResponse> observer) {
        List<Workflow> workflows;
        try {
            workflows = workflowService.list(request.getTenantId());
        } catch (IllegalArgumentException | BizException e) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            return;
        }
        WorkflowOuterClass.ListWorkflowsResponse.Builder builder =
                WorkflowOuterClass.ListWorkflowsResponse.newBuilder();
        for (Workflow workflow : workflows) {
            // 列表不携带草稿正文，避免 payload 膨胀
            builder.addWorkflows(toProtoWorkflow(workflow, false));
        }
        observer.onNext(builder.build());
        observer.onCompleted();
    }

    @Override
    public void createWorkflow(WorkflowOuterClass.CreateWorkflowRequest request,
                               StreamObserver<WorkflowOuterClass.CreateWorkflowResponse> observer) {
        try {
            Workflow workflow = workflowService.create(request.getTenantId(), request.getName(),
                    request.getDescription());
            log.info("创建工作流 workflowId={} tenantId={}", workflow.getWorkflowId(),
                    workflow.getTenantId());
            observer.onNext(WorkflowOuterClass.CreateWorkflowResponse.newBuilder()
                    .setWorkflow(toProtoWorkflow(workflow, true))
                    .build());
            observer.onCompleted();
        } catch (IllegalArgumentException e) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (BizException e) {
            observer.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void getWorkflow(WorkflowOuterClass.GetWorkflowRequest request,
                            StreamObserver<WorkflowOuterClass.GetWorkflowResponse> observer) {
        Workflow workflow;
        try {
            workflow = workflowService.get(request.getWorkflowId());
        } catch (BizException e) {
            observer.onError(notFoundOrInternal(e).asRuntimeException());
            return;
        }
        observer.onNext(WorkflowOuterClass.GetWorkflowResponse.newBuilder()
                .setWorkflow(toProtoWorkflow(workflow, true))
                .build());
        observer.onCompleted();
    }

    @Override
    public void saveDraft(WorkflowOuterClass.SaveDraftRequest request,
                          StreamObserver<WorkflowOuterClass.SaveDraftResponse> observer) {
        try {
            Workflow workflow = workflowService.saveDraft(request.getWorkflowId(), request.getDefinitionJson());
            observer.onNext(WorkflowOuterClass.SaveDraftResponse.newBuilder()
                    .setOk(true)
                    .setWorkflow(toProtoWorkflow(workflow, true))
                    .build());
        } catch (BizException e) {
            log.warn("保存草稿被拒 workflowId={} reason={}", request.getWorkflowId(), e.getMessage());
            observer.onNext(WorkflowOuterClass.SaveDraftResponse.newBuilder()
                    .setOk(false)
                    .setReason(e.getMessage())
                    .build());
        }
        observer.onCompleted();
    }

    @Override
    public void publishWorkflow(WorkflowOuterClass.PublishWorkflowRequest request,
                                StreamObserver<WorkflowOuterClass.PublishWorkflowResponse> observer) {
        try {
            int version = workflowService.publish(request.getWorkflowId(), request.getNote(),
                    request.getOperator(), request.getCanary());
            log.info("发布工作流 workflowId={} version={} canary={}", request.getWorkflowId(), version,
                    request.getCanary());
            observer.onNext(WorkflowOuterClass.PublishWorkflowResponse.newBuilder()
                    .setOk(true)
                    .setVersion(version)
                    .build());
        } catch (BizException e) {
            log.warn("发布被拒 workflowId={} reason={}", request.getWorkflowId(), e.getMessage());
            observer.onNext(WorkflowOuterClass.PublishWorkflowResponse.newBuilder()
                    .setOk(false)
                    .setReason(e.getMessage())
                    .build());
        }
        observer.onCompleted();
    }

    @Override
    public void setRollout(WorkflowOuterClass.SetRolloutRequest request,
                           StreamObserver<WorkflowOuterClass.SetRolloutResponse> observer) {
        try {
            Workflow workflow = workflowService.setRollout(request.getWorkflowId(),
                    request.getCanaryVersion(), request.getCanaryPercent(), request.getOperator());
            observer.onNext(WorkflowOuterClass.SetRolloutResponse.newBuilder()
                    .setOk(true)
                    .setWorkflow(toProtoWorkflow(workflow, true))
                    .build());
        } catch (BizException e) {
            log.warn("灰度设置被拒 workflowId={} reason={}", request.getWorkflowId(), e.getMessage());
            observer.onNext(WorkflowOuterClass.SetRolloutResponse.newBuilder()
                    .setOk(false)
                    .setReason(e.getMessage())
                    .build());
        }
        observer.onCompleted();
    }

    @Override
    public void listVersions(WorkflowOuterClass.ListVersionsRequest request,
                             StreamObserver<WorkflowOuterClass.ListVersionsResponse> observer) {
        List<WorkflowVersion> versions;
        try {
            versions = workflowService.listVersions(request.getWorkflowId());
        } catch (BizException e) {
            observer.onError(notFoundOrInternal(e).asRuntimeException());
            return;
        }
        WorkflowOuterClass.ListVersionsResponse.Builder builder =
                WorkflowOuterClass.ListVersionsResponse.newBuilder();
        for (WorkflowVersion version : versions) {
            builder.addVersions(toProtoVersionView(version));
        }
        observer.onNext(builder.build());
        observer.onCompleted();
    }

    @Override
    public void getVersion(WorkflowOuterClass.GetVersionRequest request,
                           StreamObserver<WorkflowOuterClass.GetVersionResponse> observer) {
        try {
            WorkflowVersion version = workflowService.getVersion(request.getWorkflowId(), request.getVersion());
            observer.onNext(WorkflowOuterClass.GetVersionResponse.newBuilder()
                    .setOk(true)
                    .setVersion(version.getVersion())
                    .setDefinitionJson(version.getDefinition())
                    .setNote(version.getNote())
                    .setOperator(version.getOperator())
                    .setPublishedAtMs(version.getPublishedAtMs())
                    .build());
        } catch (BizException e) {
            observer.onNext(WorkflowOuterClass.GetVersionResponse.newBuilder()
                    .setOk(false)
                    .setReason(e.getMessage())
                    .build());
        }
        observer.onCompleted();
    }

    @Override
    public void rollbackVersion(WorkflowOuterClass.RollbackVersionRequest request,
                                StreamObserver<WorkflowOuterClass.RollbackVersionResponse> observer) {
        try {
            Workflow workflow = workflowService.rollback(request.getWorkflowId(), request.getVersion(),
                    request.getOperator());
            log.info("回滚工作流草稿 workflowId={} version={}", request.getWorkflowId(), request.getVersion());
            observer.onNext(WorkflowOuterClass.RollbackVersionResponse.newBuilder()
                    .setOk(true)
                    .setWorkflow(toProtoWorkflow(workflow, true))
                    .build());
        } catch (BizException e) {
            observer.onNext(WorkflowOuterClass.RollbackVersionResponse.newBuilder()
                    .setOk(false)
                    .setReason(e.getMessage())
                    .build());
        }
        observer.onCompleted();
    }

    @Override
    public void deleteWorkflow(WorkflowOuterClass.DeleteWorkflowRequest request,
                               StreamObserver<WorkflowOuterClass.DeleteWorkflowResponse> observer) {
        try {
            workflowService.delete(request.getWorkflowId());
            log.info("删除工作流 workflowId={}", request.getWorkflowId());
            observer.onNext(WorkflowOuterClass.DeleteWorkflowResponse.newBuilder().setOk(true).build());
        } catch (BizException e) {
            observer.onNext(WorkflowOuterClass.DeleteWorkflowResponse.newBuilder()
                    .setOk(false)
                    .setReason(e.getMessage())
                    .build());
        }
        observer.onCompleted();
    }

    /** 资源不存在 → NOT_FOUND，其余业务异常 → INTERNAL。 */
    private static Status notFoundOrInternal(BizException e) {
        int code = e.getCode();
        if (code == ERR_WORKFLOW_NOT_FOUND || code == ERR_VERSION_NOT_FOUND) {
            return Status.NOT_FOUND.withDescription(e.getMessage());
        }
        return Status.INTERNAL.withDescription(e.getMessage());
    }

    /**
     * 领域工作流 → proto 工作流。
     *
     * @param withDraft 是否携带草稿正文（列表接口不携带）
     */
    private static WorkflowOuterClass.Workflow toProtoWorkflow(Workflow workflow, boolean withDraft) {
        return WorkflowOuterClass.Workflow.newBuilder()
                .setWorkflowId(workflow.getWorkflowId())
                .setTenantId(workflow.getTenantId())
                .setName(workflow.getName())
                .setDescription(workflow.getDescription())
                .setStatus(workflow.getStatus().name())
                .setDraftDefinitionJson(withDraft ? workflow.getDraftDefinition() : "")
                .setLatestVersion(workflow.getLatestVersion())
                .setPublishedVersion(workflow.getPublishedVersion())
                .setCanaryVersion(workflow.getCanaryVersion())
                .setCanaryPercent(workflow.getCanaryPercent())
                .setCreatedAtMs(workflow.getCreatedAtMs())
                .setUpdatedAtMs(workflow.getUpdatedAtMs())
                .build();
    }

    /** 版本快照 → proto 视图（不含图定义正文）。 */
    private static WorkflowOuterClass.WorkflowVersionView toProtoVersionView(WorkflowVersion version) {
        return WorkflowOuterClass.WorkflowVersionView.newBuilder()
                .setVersion(version.getVersion())
                .setNote(version.getNote())
                .setOperator(version.getOperator())
                .setPublishedAtMs(version.getPublishedAtMs())
                .setCreatedAtMs(version.getCreatedAtMs())
                .build();
    }
}
