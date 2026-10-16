package handler

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"

	"github.com/lingxi/gateway-go/internal/client"
	workflowv1 "github.com/lingxi/gateway-go/proto/workflowv1"
)

// newStudioRouter 构造仅挂载编排 Studio 路由的测试路由（注入租户 t1）。
func newStudioRouter(handler *StudioHandler) *gin.Engine {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(withTestTenant("t1"))
	router.GET("/api/v1/studio/workflows", handler.ListWorkflows)
	router.POST("/api/v1/studio/workflows", handler.CreateWorkflow)
	router.GET("/api/v1/studio/workflows/:workflow_id", handler.GetWorkflow)
	router.PUT("/api/v1/studio/workflows/:workflow_id/draft", handler.SaveDraft)
	router.POST("/api/v1/studio/workflows/:workflow_id/publish", handler.PublishWorkflow)
	router.POST("/api/v1/studio/workflows/:workflow_id/rollout", handler.SetRollout)
	router.GET("/api/v1/studio/workflows/:workflow_id/versions", handler.ListVersions)
	router.GET("/api/v1/studio/workflows/:workflow_id/versions/:version", handler.GetVersion)
	router.POST("/api/v1/studio/workflows/:workflow_id/versions/:version/rollback", handler.RollbackVersion)
	router.DELETE("/api/v1/studio/workflows/:workflow_id", handler.DeleteWorkflow)
	return router
}

// do 执行一次请求并返回响应码。
func do(router *gin.Engine, method, path, body string) int {
	var req *http.Request
	if body == "" {
		req = httptest.NewRequest(method, path, nil)
	} else {
		req = httptest.NewRequest(method, path, strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
	}
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)
	return rec.Code
}

// TestToWorkflowView proto 工作流 → JSON 视图字段映射。
func TestToWorkflowView(t *testing.T) {
	view := toWorkflowView(&workflowv1.Workflow{
		WorkflowId: "wf-1", TenantId: "t1", Name: "退款编排", Description: "d",
		Status: "ENABLED", DraftDefinitionJson: "{\"nodes\":[]}",
		LatestVersion: 2, PublishedVersion: 1, CanaryVersion: 2, CanaryPercent: 30,
		CreatedAtMs: 100, UpdatedAtMs: 200,
	})
	if view.WorkflowID != "wf-1" || view.TenantID != "t1" || view.Status != "ENABLED" {
		t.Fatalf("字段映射错误: %+v", view)
	}
	if view.LatestVersion != 2 || view.PublishedVersion != 1 {
		t.Fatalf("版本号映射错误: %+v", view)
	}
	if view.CanaryVersion != 2 || view.CanaryPercent != 30 {
		t.Fatalf("灰度字段映射错误: %+v", view)
	}
	if view.DraftDefinition != "{\"nodes\":[]}" {
		t.Fatalf("草稿映射错误: %+v", view)
	}
}

// TestToWorkflowVersionView proto 版本快照 → JSON 视图字段映射。
func TestToWorkflowVersionView(t *testing.T) {
	view := toWorkflowVersionView(&workflowv1.WorkflowVersionView{
		Version: 3, Note: "第三版", Operator: "ops", PublishedAtMs: 10, CreatedAtMs: 10,
	})
	if view.Version != 3 || view.Note != "第三版" || view.Operator != "ops" {
		t.Fatalf("版本视图映射错误: %+v", view)
	}
}

// TestParseVersionParam 版本号必须为正整数。
func TestParseVersionParam(t *testing.T) {
	if version, ok := parseVersionParam("3"); !ok || version != 3 {
		t.Fatalf("正版本号应解析成功，实际 %d/%v", version, ok)
	}
	for _, raw := range []string{"0", "-1", "abc", ""} {
		if _, ok := parseVersionParam(raw); ok {
			t.Fatalf("非法版本号 %q 应被拒绝", raw)
		}
	}
}

// TestStudioHandlerRejectsMissingParams 参数缺失/非法时返回 400（不触达后端）。
func TestStudioHandlerRejectsMissingParams(t *testing.T) {
	router := newStudioRouter(NewStudioHandler(nil))

	cases := []struct {
		name   string
		method string
		path   string
		body   string
	}{
		{"新建缺 name", http.MethodPost, "/api/v1/studio/workflows", `{"tenant_id":"t1"}`},
		{"新建缺 name（无租户字段）", http.MethodPost, "/api/v1/studio/workflows", `{}`},
		{"保存草稿缺定义", http.MethodPut, "/api/v1/studio/workflows/wf-1/draft", `{}`},
		{"保存草稿非法体", http.MethodPut, "/api/v1/studio/workflows/wf-1/draft", `not-json`},
		{"查看版本号非法", http.MethodGet, "/api/v1/studio/workflows/wf-1/versions/abc", ""},
		{"回滚版本号非法", http.MethodPost, "/api/v1/studio/workflows/wf-1/versions/0/rollback", `{}`},
		{"灰度比例越界", http.MethodPost, "/api/v1/studio/workflows/wf-1/rollout",
			`{"canary_version":2,"canary_percent":150}`},
		{"灰度比例为负", http.MethodPost, "/api/v1/studio/workflows/wf-1/rollout",
			`{"canary_version":2,"canary_percent":-1}`},
		{"开启灰度未指定版本", http.MethodPost, "/api/v1/studio/workflows/wf-1/rollout",
			`{"canary_version":0,"canary_percent":50}`},
		{"灰度请求体非法", http.MethodPost, "/api/v1/studio/workflows/wf-1/rollout", `not-json`},
		{"发布请求体非法", http.MethodPost, "/api/v1/studio/workflows/wf-1/publish", `not-json`},
	}
	for _, tc := range cases {
		if code := do(router, tc.method, tc.path, tc.body); code != http.StatusBadRequest {
			t.Fatalf("%s：期望 400，实际 %d", tc.name, code)
		}
	}
}

// TestStudioHandlerRejectsTenantForgery M5-5.5：请求体/查询串租户与身份租户不一致时拒绝（403）。
func TestStudioHandlerRejectsTenantForgery(t *testing.T) {
	router := newStudioRouter(NewStudioHandler(nil))
	if code := do(router, http.MethodGet, "/api/v1/studio/workflows?tenant_id=t2", ""); code != http.StatusForbidden {
		t.Fatalf("列表跨租户应返回 403，实际 %d", code)
	}
	if code := do(router, http.MethodPost, "/api/v1/studio/workflows",
		`{"tenant_id":"t2","name":"wf"}`); code != http.StatusForbidden {
		t.Fatalf("新建跨租户应返回 403，实际 %d", code)
	}
}

// TestStudioHandlerReturns503WhenBackendUnavailable Java 编排域不可达时返回 503。
// 指向一个未监听的本地端口，grpc 客户端为惰性连接，请求快速失败。
func TestStudioHandlerReturns503WhenBackendUnavailable(t *testing.T) {
	workflowClient, err := client.NewWorkflowService("127.0.0.1:1")
	if err != nil {
		t.Fatalf("构造客户端失败: %v", err)
	}
	defer func() { _ = workflowClient.Close() }()
	router := newStudioRouter(NewStudioHandler(workflowClient))

	cases := []struct {
		name   string
		method string
		path   string
		body   string
	}{
		{"列表", http.MethodGet, "/api/v1/studio/workflows?tenant_id=t1", ""},
		{"详情", http.MethodGet, "/api/v1/studio/workflows/wf-1", ""},
		{"保存草稿", http.MethodPut, "/api/v1/studio/workflows/wf-1/draft",
			`{"definition":{"nodes":[],"edges":[]}}`},
		{"发布", http.MethodPost, "/api/v1/studio/workflows/wf-1/publish", `{"note":"n"}`},
		{"灰度发布", http.MethodPost, "/api/v1/studio/workflows/wf-1/publish", `{"note":"n","canary":true}`},
		{"设置灰度", http.MethodPost, "/api/v1/studio/workflows/wf-1/rollout",
			`{"canary_version":2,"canary_percent":30}`},
		{"关闭灰度", http.MethodPost, "/api/v1/studio/workflows/wf-1/rollout",
			`{"canary_version":0,"canary_percent":0}`},
		{"版本列表", http.MethodGet, "/api/v1/studio/workflows/wf-1/versions", ""},
		{"删除", http.MethodDelete, "/api/v1/studio/workflows/wf-1", ""},
	}
	for _, tc := range cases {
		if code := do(router, tc.method, tc.path, tc.body); code != http.StatusServiceUnavailable {
			t.Fatalf("%s：期望 503，实际 %d", tc.name, code)
		}
	}
}
