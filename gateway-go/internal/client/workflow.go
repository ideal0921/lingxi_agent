package client

import (
	"context"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"

	workflowv1 "github.com/lingxi/gateway-go/proto/workflowv1"
)

// 编排域调用超时：与其它业务域保持一致。
const workflowTimeout = 5 * time.Second

// WorkflowService 编排域 gRPC 客户端：网关调用 Java 编排域的封装。
//
// M5-5.5：方法首参为调用方上下文，供 Java 侧从 metadata 读取可信租户做隔离校验。
type WorkflowService struct {
	conn   *grpc.ClientConn
	client workflowv1.WorkflowServiceClient
}

// NewWorkflowService 建立到 Java 编排域的 gRPC 连接。
func NewWorkflowService(addr string) (*WorkflowService, error) {
	conn, err := grpc.NewClient(addr,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
	)
	if err != nil {
		return nil, err
	}
	return &WorkflowService{
		conn:   conn,
		client: workflowv1.NewWorkflowServiceClient(conn),
	}, nil
}

// ListWorkflows 按租户列出工作流（列表不含草稿正文）。
func (s *WorkflowService) ListWorkflows(ctx context.Context, tenantID string) ([]*workflowv1.Workflow, error) {
	ctx, cancel := context.WithTimeout(ctx, workflowTimeout)
	defer cancel()
	resp, err := s.client.ListWorkflows(ctx, &workflowv1.ListWorkflowsRequest{TenantId: tenantID})
	if err != nil {
		return nil, err
	}
	return resp.Workflows, nil
}

// CreateWorkflow 新建工作流（空草稿、未发布）。
func (s *WorkflowService) CreateWorkflow(ctx context.Context, tenantID, name, description string) (*workflowv1.Workflow, error) {
	ctx, cancel := context.WithTimeout(ctx, workflowTimeout)
	defer cancel()
	resp, err := s.client.CreateWorkflow(ctx, &workflowv1.CreateWorkflowRequest{
		TenantId: tenantID, Name: name, Description: description,
	})
	if err != nil {
		return nil, err
	}
	return resp.Workflow, nil
}

// GetWorkflow 查询工作流详情（含当前草稿）。
func (s *WorkflowService) GetWorkflow(ctx context.Context, workflowID string) (*workflowv1.Workflow, error) {
	ctx, cancel := context.WithTimeout(ctx, workflowTimeout)
	defer cancel()
	resp, err := s.client.GetWorkflow(ctx, &workflowv1.GetWorkflowRequest{WorkflowId: workflowID})
	if err != nil {
		return nil, err
	}
	return resp.Workflow, nil
}

// SaveDraft 保存草稿。definitionJSON 为画布图定义的 JSON 文本。
// 返回 ok=false + reason 时表示图不可解析（业务拒绝，非通道故障）。
func (s *WorkflowService) SaveDraft(ctx context.Context, workflowID, definitionJSON string) (*workflowv1.SaveDraftResponse, error) {
	ctx, cancel := context.WithTimeout(ctx, workflowTimeout)
	defer cancel()
	return s.client.SaveDraft(ctx, &workflowv1.SaveDraftRequest{
		WorkflowId: workflowID, DefinitionJson: definitionJSON,
	})
}

// PublishWorkflow 发布当前草稿为不可变版本；校验不通过时返回 ok=false + reason。
// canary=true 为灰度发布（M4-4.8）：只推进最新版本号，生效版本不变，随后由 Rollout 指定分流比例。
func (s *WorkflowService) PublishWorkflow(ctx context.Context, workflowID, note, operator string, canary bool) (*workflowv1.PublishWorkflowResponse, error) {
	ctx, cancel := context.WithTimeout(ctx, workflowTimeout)
	defer cancel()
	return s.client.PublishWorkflow(ctx, &workflowv1.PublishWorkflowRequest{
		WorkflowId: workflowID, Note: note, Operator: operator, Canary: canary,
	})
}

// Rollout 设置灰度分流（M4-4.8）：canaryPercent=0 表示关闭灰度。
// 拒绝原因（比例越界/灰度版本不存在）以 ok=false + reason 返回。
func (s *WorkflowService) Rollout(ctx context.Context, workflowID string, canaryVersion, canaryPercent int32, operator string) (*workflowv1.SetRolloutResponse, error) {
	ctx, cancel := context.WithTimeout(ctx, workflowTimeout)
	defer cancel()
	return s.client.SetRollout(ctx, &workflowv1.SetRolloutRequest{
		WorkflowId:    workflowID,
		CanaryVersion: canaryVersion,
		CanaryPercent: canaryPercent,
		Operator:      operator,
	})
}

// ListVersions 列出版本快照（版本号倒序）。
func (s *WorkflowService) ListVersions(ctx context.Context, workflowID string) ([]*workflowv1.WorkflowVersionView, error) {
	ctx, cancel := context.WithTimeout(ctx, workflowTimeout)
	defer cancel()
	resp, err := s.client.ListVersions(ctx, &workflowv1.ListVersionsRequest{WorkflowId: workflowID})
	if err != nil {
		return nil, err
	}
	return resp.Versions, nil
}

// GetVersion 查询某版本快照正文（只读，不影响草稿）。
func (s *WorkflowService) GetVersion(ctx context.Context, workflowID string, version int32) (*workflowv1.GetVersionResponse, error) {
	ctx, cancel := context.WithTimeout(ctx, workflowTimeout)
	defer cancel()
	return s.client.GetVersion(ctx, &workflowv1.GetVersionRequest{
		WorkflowId: workflowID, Version: version,
	})
}

// RollbackVersion 回滚：把指定版本内容复制为当前草稿（不新增版本、不改生效版本）。
func (s *WorkflowService) RollbackVersion(ctx context.Context, workflowID string, version int32, operator string) (*workflowv1.RollbackVersionResponse, error) {
	ctx, cancel := context.WithTimeout(ctx, workflowTimeout)
	defer cancel()
	return s.client.RollbackVersion(ctx, &workflowv1.RollbackVersionRequest{
		WorkflowId: workflowID, Version: version, Operator: operator,
	})
}

// DeleteWorkflow 删除工作流（连带清除版本快照）。
func (s *WorkflowService) DeleteWorkflow(ctx context.Context, workflowID string) (*workflowv1.DeleteWorkflowResponse, error) {
	ctx, cancel := context.WithTimeout(ctx, workflowTimeout)
	defer cancel()
	return s.client.DeleteWorkflow(ctx, &workflowv1.DeleteWorkflowRequest{WorkflowId: workflowID})
}

// Close 关闭连接。
func (s *WorkflowService) Close() error {
	return s.conn.Close()
}
