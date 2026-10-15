package handler

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"

	"github.com/lingxi/gateway-go/internal/client"
	reportv1 "github.com/lingxi/gateway-go/proto/reportv1"
)

// 评分合法区间（与会话域校验保持一致）。
const (
	minRating = 1
	maxRating = 5
)

// ReportHandler 运营报表与满意度评价接口（M5-5.1）。
// 职责边界：仅做协议转换与参数校验（缺参/越界 → 400，通道故障 → 503）；
// 聚合口径、租户归属解析与落库幂等收敛到 Java 报表域 / 会话域。
type ReportHandler struct {
	report  *client.ReportService
	session *client.SessionService
}

// NewReportHandler 创建报表处理器。
func NewReportHandler(report *client.ReportService, session *client.SessionService) *ReportHandler {
	return &ReportHandler{report: report, session: session}
}

// funnelStageView 漏斗阶段 JSON 视图。
type funnelStageView struct {
	Stage     string  `json:"stage"`
	Label     string  `json:"label"`
	Sessions  int32   `json:"sessions"`
	StepRate  float64 `json:"step_rate"`
	TotalRate float64 `json:"total_rate"`
}

// tokenUsageView Token 消耗明细 JSON 视图（日 × 提供方 × 模型）。
type tokenUsageView struct {
	StatDate          string `json:"stat_date"`
	Provider          string `json:"provider"`
	Model             string `json:"model"`
	Requests          int32  `json:"requests"`
	PromptTokens      int64  `json:"prompt_tokens"`
	CompletionTokens  int64  `json:"completion_tokens"`
	EstimatedRequests int32  `json:"estimated_requests"`
	FirstTokenMsAvg   int64  `json:"first_token_ms_avg"`
}

// ratingBucketView 评分分布条目 JSON 视图。
type ratingBucketView struct {
	Rating int32 `json:"rating"`
	Count  int32 `json:"count"`
}

// feedbackView 评价明细 JSON 视图。
type feedbackView struct {
	SessionID   string `json:"session_id"`
	Rating      int32  `json:"rating"`
	Comment     string `json:"comment"`
	CreatedAtMs int64  `json:"created_at_ms"`
}

// reportRange 报表查询区间（from/to 为 0 表示缺省，由 Java 报表域套用默认窗口）。
type reportRange struct {
	tenantID string
	fromMs   int64
	toMs     int64
}

// GetFunnel 会话漏斗报表（各阶段会话数 + 相邻转化率 + 累计转化率）。
// 路由：GET /api/v1/reports/funnel?tenant_id=t1&from=<epochMs>&to=<epochMs>
func (h *ReportHandler) GetFunnel(c *gin.Context) {
	query, ok := parseReportRange(c)
	if !ok {
		return
	}
	report, err := h.report.GetFunnelReport(query.tenantID, query.fromMs, query.toMs)
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "获取会话漏斗失败"})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"created_sessions": report.CreatedSessions,
		"stages":           toFunnelStages(report.Stages),
	})
}

// GetTokens Token 消耗报表（日 × 提供方 × 模型 明细 + 合计）。
// 路由：GET /api/v1/reports/tokens?tenant_id=t1&from=<epochMs>&to=<epochMs>
func (h *ReportHandler) GetTokens(c *gin.Context) {
	query, ok := parseReportRange(c)
	if !ok {
		return
	}
	report, err := h.report.GetTokenReport(query.tenantID, query.fromMs, query.toMs)
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "获取 Token 消耗失败"})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"total_requests":           report.TotalRequests,
		"total_prompt_tokens":      report.TotalPromptTokens,
		"total_completion_tokens":  report.TotalCompletionTokens,
		"total_estimated_requests": sumEstimated(report.Rows),
		"rows":                     toTokenUsages(report.Rows),
	})
}

// GetCsat 满意度报表（CSAT / 平均分 / 评分分布 / 最近评价）。
// 路由：GET /api/v1/reports/csat?tenant_id=t1&from=<epochMs>&to=<epochMs>
func (h *ReportHandler) GetCsat(c *gin.Context) {
	query, ok := parseReportRange(c)
	if !ok {
		return
	}
	report, err := h.report.GetCsatReport(query.tenantID, query.fromMs, query.toMs)
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "获取满意度失败"})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"feedback_count": report.FeedbackCount,
		"average_rating": report.AverageRating,
		"csat":           report.Csat,
		"distribution":   toRatingBuckets(report.Distribution),
		"recent":         toFeedbackItems(report.Recent),
	})
}

// SubmitFeedback 提交会话满意度评价（用户端门户）。
// 租户/用户由 Java 会话域按会话解析，故请求体不接收 tenant_id（防跨租户伪造）；
// 评分越界 → 400，会话不存在等业务拒绝以 200 + ok=false + reason 返回。
// 路由：POST /api/v1/session/:session_id/feedback {rating, comment?, tags?}
func (h *ReportHandler) SubmitFeedback(c *gin.Context) {
	sessionID := c.Param("session_id")
	if sessionID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "session_id 不能为空"})
		return
	}
	var req struct {
		Rating  int32  `json:"rating"`
		Comment string `json:"comment"`
		Tags    string `json:"tags"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "非法请求体"})
		return
	}
	if req.Rating < minRating || req.Rating > maxRating {
		c.JSON(http.StatusBadRequest, gin.H{"error": "rating 必须在 1~5 之间"})
		return
	}
	resp, err := h.session.SubmitFeedback(sessionID, req.Rating, req.Comment, req.Tags)
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "提交评价失败"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": resp.Ok, "reason": resp.Reason})
}

// parseReportRange 解析并校验报表公共查询参数。
// tenant_id 必填；from/to 可选（缺省交由 Java 报表域套用最近 7 天）。
// 校验不通过时直接写回 400 并返回 ok=false，调用方据此提前返回。
func parseReportRange(c *gin.Context) (reportRange, bool) {
	tenantID := c.Query("tenant_id")
	if tenantID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "tenant_id 不能为空"})
		return reportRange{}, false
	}
	fromMs, ok := parseEpochMs(c, c.Query("from"), "from")
	if !ok {
		return reportRange{}, false
	}
	toMs, ok := parseEpochMs(c, c.Query("to"), "to")
	if !ok {
		return reportRange{}, false
	}
	if fromMs > 0 && toMs > 0 && fromMs >= toMs {
		c.JSON(http.StatusBadRequest, gin.H{"error": "from 必须小于 to"})
		return reportRange{}, false
	}
	return reportRange{tenantID: tenantID, fromMs: fromMs, toMs: toMs}, true
}

// parseEpochMs 解析 epoch 毫秒参数：缺省（空串）返回 0，非法或为负时写回 400 并返回 false。
func parseEpochMs(c *gin.Context, raw, field string) (int64, bool) {
	if raw == "" {
		return 0, true
	}
	value, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || value < 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": field + " 必须为非负的 epoch 毫秒整数"})
		return 0, false
	}
	return value, true
}

// sumEstimated 汇总明细行的估算条数（协议未单列该合计，由视图层纯计算得出）。
func sumEstimated(rows []*reportv1.TokenUsageRow) int32 {
	var total int32
	for _, row := range rows {
		total += row.EstimatedRequests
	}
	return total
}

// toFunnelStages proto 漏斗阶段 → JSON 视图（纯函数，便于单测）。
func toFunnelStages(stages []*reportv1.FunnelStageCount) []funnelStageView {
	views := make([]funnelStageView, 0, len(stages))
	for _, stage := range stages {
		views = append(views, funnelStageView{
			Stage:     stage.Stage,
			Label:     stage.Label,
			Sessions:  stage.Sessions,
			StepRate:  stage.StepRate,
			TotalRate: stage.TotalRate,
		})
	}
	return views
}

// toTokenUsages proto Token 明细 → JSON 视图（纯函数，便于单测）。
func toTokenUsages(rows []*reportv1.TokenUsageRow) []tokenUsageView {
	views := make([]tokenUsageView, 0, len(rows))
	for _, row := range rows {
		views = append(views, tokenUsageView{
			StatDate:          row.StatDate,
			Provider:          row.Provider,
			Model:             row.Model,
			Requests:          row.Requests,
			PromptTokens:      row.PromptTokens,
			CompletionTokens:  row.CompletionTokens,
			EstimatedRequests: row.EstimatedRequests,
			FirstTokenMsAvg:   row.FirstTokenMsAvg,
		})
	}
	return views
}

// toRatingBuckets proto 评分分布 → JSON 视图（纯函数，便于单测）。
func toRatingBuckets(buckets []*reportv1.RatingBucket) []ratingBucketView {
	views := make([]ratingBucketView, 0, len(buckets))
	for _, bucket := range buckets {
		views = append(views, ratingBucketView{Rating: bucket.Rating, Count: bucket.Count})
	}
	return views
}

// toFeedbackItems proto 评价明细 → JSON 视图（纯函数，便于单测）。
func toFeedbackItems(items []*reportv1.FeedbackItem) []feedbackView {
	views := make([]feedbackView, 0, len(items))
	for _, item := range items {
		views = append(views, feedbackView{
			SessionID:   item.SessionId,
			Rating:      item.Rating,
			Comment:     item.Comment,
			CreatedAtMs: item.CreatedAtMs,
		})
	}
	return views
}
