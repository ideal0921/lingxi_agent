package handler

import (
	"net/http"
	"testing"

	"github.com/gin-gonic/gin"

	"github.com/lingxi/gateway-go/internal/client"
	reportv1 "github.com/lingxi/gateway-go/proto/reportv1"
)

// newReportRouter 构造仅挂载报表与评价路由的测试路由。
func newReportRouter(handler *ReportHandler) *gin.Engine {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.GET("/api/v1/reports/funnel", handler.GetFunnel)
	router.GET("/api/v1/reports/tokens", handler.GetTokens)
	router.GET("/api/v1/reports/csat", handler.GetCsat)
	router.POST("/api/v1/session/:session_id/feedback", handler.SubmitFeedback)
	return router
}

// TestToFunnelStages 漏斗阶段 proto → JSON 视图字段映射。
func TestToFunnelStages(t *testing.T) {
	views := toFunnelStages([]*reportv1.FunnelStageCount{
		{Stage: "created", Label: "建会话", Sessions: 10, StepRate: 1.0, TotalRate: 1.0},
		{Stage: "user_msg", Label: "首条消息", Sessions: 8, StepRate: 0.8, TotalRate: 0.8},
	})
	if len(views) != 2 {
		t.Fatalf("阶段条数错误: %d", len(views))
	}
	if views[0].Stage != "created" || views[0].Label != "建会话" || views[0].Sessions != 10 {
		t.Fatalf("首阶段映射错误: %+v", views[0])
	}
	if views[1].StepRate != 0.8 || views[1].TotalRate != 0.8 {
		t.Fatalf("转化率映射错误: %+v", views[1])
	}
}

// TestToTokenUsagesAndEstimatedTotal Token 明细映射与估算条数合计（协议未单列合计，由视图层计算）。
func TestToTokenUsagesAndEstimatedTotal(t *testing.T) {
	rows := []*reportv1.TokenUsageRow{
		{
			StatDate: "2026-10-15", Provider: "deepseek", Model: "deepseek-chat",
			Requests: 3, PromptTokens: 100, CompletionTokens: 50,
			EstimatedRequests: 1, FirstTokenMsAvg: 420,
		},
		{
			StatDate: "2026-10-15", Provider: "qwen", Model: "qwen-plus",
			Requests: 2, PromptTokens: 80, CompletionTokens: 20, EstimatedRequests: 2,
		},
	}
	views := toTokenUsages(rows)
	if len(views) != 2 || views[0].Provider != "deepseek" || views[0].PromptTokens != 100 {
		t.Fatalf("Token 明细映射错误: %+v", views)
	}
	if views[0].FirstTokenMsAvg != 420 {
		t.Fatalf("首字延迟映射错误: %+v", views[0])
	}
	if total := sumEstimated(rows); total != 3 {
		t.Fatalf("估算条数合计错误：期望 3，实际 %d", total)
	}
	if empty := sumEstimated(nil); empty != 0 {
		t.Fatalf("空明细合计应为 0，实际 %d", empty)
	}
}

// TestToRatingBucketsAndFeedbackItems 满意度分布与最近评价映射。
func TestToRatingBucketsAndFeedbackItems(t *testing.T) {
	buckets := toRatingBuckets([]*reportv1.RatingBucket{{Rating: 5, Count: 3}, {Rating: 4, Count: 1}})
	if len(buckets) != 2 || buckets[0].Rating != 5 || buckets[0].Count != 3 {
		t.Fatalf("评分分布映射错误: %+v", buckets)
	}
	items := toFeedbackItems([]*reportv1.FeedbackItem{
		{SessionId: "s-1", Rating: 5, Comment: "很好", CreatedAtMs: 100},
	})
	if len(items) != 1 || items[0].SessionID != "s-1" || items[0].Comment != "很好" {
		t.Fatalf("评价明细映射错误: %+v", items)
	}
}

// TestReportHandlerRejectsBadParams 参数缺失/非法时返回 400（不触达后端）。
func TestReportHandlerRejectsBadParams(t *testing.T) {
	router := newReportRouter(NewReportHandler(nil, nil))

	cases := []struct {
		name   string
		method string
		path   string
		body   string
	}{
		{"漏斗缺 tenant_id", http.MethodGet, "/api/v1/reports/funnel", ""},
		{"Token 缺 tenant_id", http.MethodGet, "/api/v1/reports/tokens", ""},
		{"满意度缺 tenant_id", http.MethodGet, "/api/v1/reports/csat", ""},
		{"from 非整数", http.MethodGet, "/api/v1/reports/funnel?tenant_id=t1&from=abc", ""},
		{"from 为负", http.MethodGet, "/api/v1/reports/funnel?tenant_id=t1&from=-1", ""},
		{"to 非整数", http.MethodGet, "/api/v1/reports/tokens?tenant_id=t1&to=x", ""},
		{"区间反置", http.MethodGet, "/api/v1/reports/csat?tenant_id=t1&from=200&to=100", ""},
		{"区间相等", http.MethodGet, "/api/v1/reports/csat?tenant_id=t1&from=100&to=100", ""},
		{"评价缺评分", http.MethodPost, "/api/v1/session/s-1/feedback", `{"comment":"没有评分"}`},
		{"评分越界（高）", http.MethodPost, "/api/v1/session/s-1/feedback", `{"rating":6}`},
		{"评分越界（低）", http.MethodPost, "/api/v1/session/s-1/feedback", `{"rating":-1}`},
		{"评分请求体非法", http.MethodPost, "/api/v1/session/s-1/feedback", `not-json`},
	}
	for _, tc := range cases {
		if code := do(router, tc.method, tc.path, tc.body); code != http.StatusBadRequest {
			t.Fatalf("%s：期望 400，实际 %d", tc.name, code)
		}
	}
}

// TestReportHandlerReturns503WhenBackendUnavailable Java 报表域/会话域不可达时返回 503。
// 指向一个未监听的本地端口，grpc 客户端为惰性连接，请求快速失败。
func TestReportHandlerReturns503WhenBackendUnavailable(t *testing.T) {
	reportClient, err := client.NewReportService("127.0.0.1:1")
	if err != nil {
		t.Fatalf("构造报表客户端失败: %v", err)
	}
	defer func() { _ = reportClient.Close() }()
	sessionClient, err := client.NewSessionService("127.0.0.1:1")
	if err != nil {
		t.Fatalf("构造会话客户端失败: %v", err)
	}
	defer func() { _ = sessionClient.Close() }()

	router := newReportRouter(NewReportHandler(reportClient, sessionClient))

	cases := []struct {
		name   string
		method string
		path   string
		body   string
	}{
		{"漏斗", http.MethodGet, "/api/v1/reports/funnel?tenant_id=t1", ""},
		{"Token", http.MethodGet, "/api/v1/reports/tokens?tenant_id=t1&from=1&to=2", ""},
		{"满意度", http.MethodGet, "/api/v1/reports/csat?tenant_id=t1", ""},
		{"提交评价", http.MethodPost, "/api/v1/session/s-1/feedback", `{"rating":5,"comment":"很好"}`},
	}
	for _, tc := range cases {
		if code := do(router, tc.method, tc.path, tc.body); code != http.StatusServiceUnavailable {
			t.Fatalf("%s：期望 503，实际 %d", tc.name, code)
		}
	}
}
