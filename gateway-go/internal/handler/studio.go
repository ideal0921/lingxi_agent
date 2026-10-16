package handler

import (
	"encoding/json"
	"log"
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"

	"github.com/lingxi/gateway-go/internal/client"
	"github.com/lingxi/gateway-go/internal/httpx"
	"github.com/lingxi/gateway-go/internal/tenant"
	workflowv1 "github.com/lingxi/gateway-go/proto/workflowv1"
)

// StudioHandler 编排 Studio 接口：工作流 CRUD、草稿保存、版本发布与回滚。
// 职责边界：仅做协议转换与转发，图定义校验与版本冻结规则收敛到 Java 编排域。
type StudioHandler struct {
	workflow *client.WorkflowService
}

// NewStudioHandler 创建编排 Studio 处理器。
func NewStudioHandler(workflow *client.WorkflowService) *StudioHandler {
	return &StudioHandler{workflow: workflow}
}

// workflowView 工作流 JSON 视图。
type workflowView struct {
	WorkflowID       string `json:"workflow_id"`
	TenantID         string `json:"tenant_id"`
	Name             string `json:"name"`
	Description      string `json:"description"`
	Status           string `json:"status"`
	DraftDefinition  string `json:"draft_definition_json"`
	LatestVersion    int32  `json:"latest_version"`
	PublishedVersion int32  `json:"published_version"`
	// 灰度分流（M4-4.8）：canary_percent=0 表示未启用灰度
	CanaryVersion int32 `json:"canary_version"`
	CanaryPercent int32 `json:"canary_percent"`
	CreatedAtMs   int64 `json:"created_at_ms"`
	UpdatedAtMs   int64 `json:"updated_at_ms"`
}

// workflowVersionView 版本快照 JSON 视图（不含图定义正文）。
type workflowVersionView struct {
	Version       int32  `json:"version"`
	Note          string `json:"note"`
	Operator      string `json:"operator"`
	PublishedAtMs int64  `json:"published_at_ms"`
	CreatedAtMs   int64  `json:"created_at_ms"`
}

// ListWorkflows 列出当前租户的工作流。
// 路由：GET /api/v1/studio/workflows（租户取可信上下文；查询串 tenant_id 不一致即 403）
func (h *StudioHandler) ListWorkflows(c *gin.Context) {
	identity, ok := tenant.FromGin(c)
	if !ok || !identity.HasTenant() {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "缺少租户上下文"})
		return
	}
	if requested := c.Query("tenant_id"); requested != "" && requested != identity.TenantID {
		log.Printf("[tenant-isolation-denied] 工作流列表租户伪造已拒绝 tenant=%s requested=%s",
			identity.TenantID, requested)
		c.JSON(http.StatusForbidden, gin.H{"error": "请求租户与身份租户不一致"})
		return
	}
	workflows, err := h.workflow.ListWorkflows(tenant.ClientContext(c), identity.TenantID)
	if err != nil {
		c.JSON(httpx.StatusFromGRPC(err), gin.H{"error": "获取工作流列表失败"})
		return
	}
	views := make([]workflowView, 0, len(workflows))
	for _, workflow := range workflows {
		views = append(views, toWorkflowView(workflow))
	}
	c.JSON(http.StatusOK, gin.H{"workflows": views})
}

// CreateWorkflow 新建工作流（租户取可信上下文；请求体 tenant_id 不一致即 403）。
// 路由：POST /api/v1/studio/workflows {name, description?, tenant_id?}
func (h *StudioHandler) CreateWorkflow(c *gin.Context) {
	var req struct {
		TenantID    string `json:"tenant_id"`
		Name        string `json:"name"`
		Description string `json:"description"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.Name == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "name 不能为空"})
		return
	}
	identity, ok := tenant.FromGin(c)
	if !ok || !identity.HasTenant() {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "缺少租户上下文"})
		return
	}
	if req.TenantID != "" && req.TenantID != identity.TenantID {
		log.Printf("[tenant-isolation-denied] 新建工作流租户伪造已拒绝 tenant=%s requested=%s",
			identity.TenantID, req.TenantID)
		c.JSON(http.StatusForbidden, gin.H{"error": "请求租户与身份租户不一致"})
		return
	}
	workflow, err := h.workflow.CreateWorkflow(tenant.ClientContext(c), identity.TenantID, req.Name, req.Description)
	if err != nil {
		c.JSON(httpx.StatusFromGRPC(err), gin.H{"error": "新建工作流失败"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"workflow": toWorkflowView(workflow)})
}

// GetWorkflow 查询工作流详情（含当前草稿）。
// 路由：GET /api/v1/studio/workflows/:workflow_id
func (h *StudioHandler) GetWorkflow(c *gin.Context) {
	workflowID := c.Param("workflow_id")
	if workflowID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "workflow_id 不能为空"})
		return
	}
	workflow, err := h.workflow.GetWorkflow(tenant.ClientContext(c), workflowID)
	if err != nil {
		c.JSON(httpx.StatusFromGRPC(err), gin.H{"error": "工作流不存在或查询失败"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"workflow": toWorkflowView(workflow)})
}

// SaveDraft 保存草稿：请求体直接承载画布图对象（definition），由 Go 原样转成 JSON 文本上送。
// 路由：PUT /api/v1/studio/workflows/:workflow_id/draft {definition}
func (h *StudioHandler) SaveDraft(c *gin.Context) {
	workflowID := c.Param("workflow_id")
	if workflowID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "workflow_id 不能为空"})
		return
	}
	var req struct {
		Definition json.RawMessage `json:"definition"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || len(req.Definition) == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "definition（画布图定义）不能为空"})
		return
	}
	resp, err := h.workflow.SaveDraft(tenant.ClientContext(c), workflowID, string(req.Definition))
	if err != nil {
		c.JSON(httpx.StatusFromGRPC(err), gin.H{"error": "保存草稿失败"})
		return
	}
	result := gin.H{"ok": resp.Ok, "reason": resp.Reason}
	if resp.Workflow != nil {
		result["workflow"] = toWorkflowView(resp.Workflow)
	}
	c.JSON(http.StatusOK, result)
}

// PublishWorkflow 发布当前草稿为不可变版本。
// 校验不通过返回 ok=false + reason（业务拒绝），通道故障返回 503。
// canary=true 为灰度发布（M4-4.8）：只推进最新版本号，生效版本不变。
// 路由：POST /api/v1/studio/workflows/:workflow_id/publish {note?, operator?, canary?}
func (h *StudioHandler) PublishWorkflow(c *gin.Context) {
	workflowID := c.Param("workflow_id")
	if workflowID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "workflow_id 不能为空"})
		return
	}
	var req struct {
		Note     string `json:"note"`
		Operator string `json:"operator"`
		Canary   bool   `json:"canary"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "非法请求体"})
		return
	}
	resp, err := h.workflow.PublishWorkflow(tenant.ClientContext(c), workflowID, req.Note, req.Operator, req.Canary)
	if err != nil {
		c.JSON(httpx.StatusFromGRPC(err), gin.H{"error": "发布工作流失败"})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"ok": resp.Ok, "reason": resp.Reason, "version": resp.Version, "canary": req.Canary,
	})
}

// SetRollout 设置灰度分流（M4-4.8）：canary_percent=0 表示关闭灰度（灰度版本归 0）。
// 比例越界或灰度版本不存在时由 Java 编排域拒绝，返回 ok=false + reason。
// 路由：POST /api/v1/studio/workflows/:workflow_id/rollout {canary_version, canary_percent, operator?}
func (h *StudioHandler) SetRollout(c *gin.Context) {
	workflowID := c.Param("workflow_id")
	if workflowID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "workflow_id 不能为空"})
		return
	}
	var req struct {
		CanaryVersion int32  `json:"canary_version"`
		CanaryPercent int32  `json:"canary_percent"`
		Operator      string `json:"operator"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "非法请求体"})
		return
	}
	// 网关只做范围校验，业务规则（版本是否存在等）收敛到 Java 编排域
	if req.CanaryPercent < 0 || req.CanaryPercent > 100 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "canary_percent 必须在 0~100 之间"})
		return
	}
	if req.CanaryPercent > 0 && req.CanaryVersion <= 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "开启灰度时必须指定 canary_version"})
		return
	}
	resp, err := h.workflow.Rollout(tenant.ClientContext(c), workflowID, req.CanaryVersion, req.CanaryPercent, req.Operator)
	if err != nil {
		c.JSON(httpx.StatusFromGRPC(err), gin.H{"error": "设置灰度失败"})
		return
	}
	result := gin.H{"ok": resp.Ok, "reason": resp.Reason}
	if resp.Workflow != nil {
		result["workflow"] = toWorkflowView(resp.Workflow)
	}
	c.JSON(http.StatusOK, result)
}

// ListVersions 列出版本快照（版本号倒序）。
// 路由：GET /api/v1/studio/workflows/:workflow_id/versions
func (h *StudioHandler) ListVersions(c *gin.Context) {
	workflowID := c.Param("workflow_id")
	if workflowID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "workflow_id 不能为空"})
		return
	}
	versions, err := h.workflow.ListVersions(tenant.ClientContext(c), workflowID)
	if err != nil {
		c.JSON(httpx.StatusFromGRPC(err), gin.H{"error": "获取版本列表失败"})
		return
	}
	views := make([]workflowVersionView, 0, len(versions))
	for _, version := range versions {
		views = append(views, toWorkflowVersionView(version))
	}
	c.JSON(http.StatusOK, gin.H{"versions": views})
}

// GetVersion 查询某版本快照正文（只读）。
// 路由：GET /api/v1/studio/workflows/:workflow_id/versions/:version
func (h *StudioHandler) GetVersion(c *gin.Context) {
	workflowID := c.Param("workflow_id")
	version, ok := parseVersionParam(c.Param("version"))
	if workflowID == "" || !ok {
		c.JSON(http.StatusBadRequest, gin.H{"error": "workflow_id 不能为空且 version 必须为正整数"})
		return
	}
	resp, err := h.workflow.GetVersion(tenant.ClientContext(c), workflowID, version)
	if err != nil {
		c.JSON(httpx.StatusFromGRPC(err), gin.H{"error": "查询版本失败"})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"ok": resp.Ok, "reason": resp.Reason, "version": resp.Version,
		"definition_json": resp.DefinitionJson, "note": resp.Note,
		"operator": resp.Operator, "published_at_ms": resp.PublishedAtMs,
	})
}

// RollbackVersion 回滚：把指定版本内容复制为当前草稿（不新增版本、不改生效版本）。
// 路由：POST /api/v1/studio/workflows/:workflow_id/versions/:version/rollback {operator?}
func (h *StudioHandler) RollbackVersion(c *gin.Context) {
	workflowID := c.Param("workflow_id")
	version, ok := parseVersionParam(c.Param("version"))
	if workflowID == "" || !ok {
		c.JSON(http.StatusBadRequest, gin.H{"error": "workflow_id 不能为空且 version 必须为正整数"})
		return
	}
	// 请求体可选：前端只回传操作者，允许空体
	var req struct {
		Operator string `json:"operator"`
	}
	_ = c.ShouldBindJSON(&req)
	resp, err := h.workflow.RollbackVersion(tenant.ClientContext(c), workflowID, version, req.Operator)
	if err != nil {
		c.JSON(httpx.StatusFromGRPC(err), gin.H{"error": "回滚失败"})
		return
	}
	result := gin.H{"ok": resp.Ok, "reason": resp.Reason}
	if resp.Workflow != nil {
		result["workflow"] = toWorkflowView(resp.Workflow)
	}
	c.JSON(http.StatusOK, result)
}

// DeleteWorkflow 删除工作流（连带清除版本快照）。
// 路由：DELETE /api/v1/studio/workflows/:workflow_id
func (h *StudioHandler) DeleteWorkflow(c *gin.Context) {
	workflowID := c.Param("workflow_id")
	if workflowID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "workflow_id 不能为空"})
		return
	}
	resp, err := h.workflow.DeleteWorkflow(tenant.ClientContext(c), workflowID)
	if err != nil {
		c.JSON(httpx.StatusFromGRPC(err), gin.H{"error": "删除工作流失败"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": resp.Ok, "reason": resp.Reason})
}

// toWorkflowView proto 工作流 → JSON 视图（纯函数，便于单测）。
func toWorkflowView(workflow *workflowv1.Workflow) workflowView {
	return workflowView{
		WorkflowID:       workflow.WorkflowId,
		TenantID:         workflow.TenantId,
		Name:             workflow.Name,
		Description:      workflow.Description,
		Status:           workflow.Status,
		DraftDefinition:  workflow.DraftDefinitionJson,
		LatestVersion:    workflow.LatestVersion,
		PublishedVersion: workflow.PublishedVersion,
		CanaryVersion:    workflow.CanaryVersion,
		CanaryPercent:    workflow.CanaryPercent,
		CreatedAtMs:      workflow.CreatedAtMs,
		UpdatedAtMs:      workflow.UpdatedAtMs,
	}
}

// toWorkflowVersionView proto 版本快照 → JSON 视图（纯函数，便于单测）。
func toWorkflowVersionView(version *workflowv1.WorkflowVersionView) workflowVersionView {
	return workflowVersionView{
		Version:       version.Version,
		Note:          version.Note,
		Operator:      version.Operator,
		PublishedAtMs: version.PublishedAtMs,
		CreatedAtMs:   version.CreatedAtMs,
	}
}

// parseVersionParam 解析路径中的版本号：必须为正整数。
func parseVersionParam(raw string) (int32, bool) {
	parsed, err := strconv.ParseInt(raw, 10, 32)
	if err != nil || parsed <= 0 {
		return 0, false
	}
	return int32(parsed), true
}
