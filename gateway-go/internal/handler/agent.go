package handler

import (
	"log"
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"github.com/lingxi/gateway-go/internal/client"
	"github.com/lingxi/gateway-go/internal/httpx"
	"github.com/lingxi/gateway-go/internal/tenant"
	sessionv1 "github.com/lingxi/gateway-go/proto/sessionv1"
	ticketv1 "github.com/lingxi/gateway-go/proto/ticketv1"
)

// AgentHandler 客服工作台接口：队列/接管/会话列表与工单操作。
// 职责边界：仅做协议转换与转发，业务校验收敛到 Java 业务域。
type AgentHandler struct {
	session *client.SessionService
	ticket  *client.TicketService
}

// NewAgentHandler 创建工作台处理器。
func NewAgentHandler(session *client.SessionService, ticket *client.TicketService) *AgentHandler {
	return &AgentHandler{session: session, ticket: ticket}
}

// sessionSummary 会话摘要的 JSON 视图。
type sessionSummary struct {
	SessionID    string `json:"session_id"`
	UserID       string `json:"user_id"`
	State        string `json:"state"`
	AgentID      string `json:"agent_id"`
	CreatedAtMs  int64  `json:"created_at_ms"`
	UpdatedAtMs  int64  `json:"updated_at_ms"`
	MessageCount int32  `json:"message_count"`
	Summary      string `json:"summary,omitempty"`
}

// suggestView 回复建议的 JSON 视图（M4-4.6 同步通道）。
type suggestView struct {
	RequestID     string   `json:"request_id"`
	Ok            bool     `json:"ok"`
	Degraded      bool     `json:"degraded"`
	DegradeReason string   `json:"degrade_reason"`
	Queued        bool     `json:"queued"`
	Suggestions   []string `json:"suggestions"`
	ElapsedMs     int64    `json:"elapsed_ms"`
}

// ticketView 工单的 JSON 视图。
type ticketView struct {
	TicketID    string `json:"ticket_id"`
	TenantID    string `json:"tenant_id"`
	SessionID   string `json:"session_id"`
	UserID      string `json:"user_id"`
	AgentID     string `json:"agent_id"`
	Title       string `json:"title"`
	Description string `json:"description"`
	Status      string `json:"status"`
	CreatedAtMs int64  `json:"created_at_ms"`
	UpdatedAtMs int64  `json:"updated_at_ms"`
}

// ListQueue 客服队列：待接管会话。
// 路由：GET /api/v1/agent/queue?limit=20
func (h *AgentHandler) ListQueue(c *gin.Context) {
	limit := int32(20)
	if v := c.Query("limit"); v != "" {
		if parsed, err := strconv.ParseInt(v, 10, 32); err == nil && parsed > 0 {
			limit = int32(parsed)
		}
	}
	sessions, err := h.session.ListWaitingSessions(tenant.ClientContext(c), limit)
	if err != nil {
		c.JSON(httpx.StatusFromGRPC(err), gin.H{"error": "获取客服队列失败"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"sessions": toSessionSummaries(sessions)})
}

// TakeOver 客服接管：抢单仅成功一次（Java 状态机保证）。
// 路由：POST /api/v1/agent/takeover {session_id, agent_id}
func (h *AgentHandler) TakeOver(c *gin.Context) {
	var req struct {
		SessionID string `json:"session_id"`
		AgentID   string `json:"agent_id"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.SessionID == "" || req.AgentID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "session_id 与 agent_id 不能为空"})
		return
	}
	resp, err := h.session.TakeOver(tenant.ClientContext(c), req.SessionID, req.AgentID)
	if err != nil {
		c.JSON(httpx.StatusFromGRPC(err), gin.H{"error": "接管请求失败"})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"ok": resp.Ok, "reason": resp.Reason, "state": resp.State.String(),
	})
}

// ListAgentSessions 指定坐席的会话列表。
// 路由：GET /api/v1/agent/sessions?agent_id=xxx
func (h *AgentHandler) ListAgentSessions(c *gin.Context) {
	agentID := c.Query("agent_id")
	if agentID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "agent_id 不能为空"})
		return
	}
	sessions, err := h.session.ListAgentSessions(tenant.ClientContext(c), agentID)
	if err != nil {
		c.JSON(httpx.StatusFromGRPC(err), gin.H{"error": "获取坐席会话失败"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"sessions": toSessionSummaries(sessions)})
}

// SuggestReply 快捷回复：调 Java 同步通道取 1~3 条候选草稿，供坐席选择/编辑后再发送。
// 过载降级（degraded+queued）时结果稍后经 Kafka 由坐席端 WSS 推送。
// request_id 由 Go 生成（uuid）并透传三端，用于同步/异步结果去重。
// 路由：POST /api/v1/agent/suggest {session_id, agent_id, intent?, max_suggestions?}
func (h *AgentHandler) SuggestReply(c *gin.Context) {
	var req struct {
		SessionID      string `json:"session_id"`
		AgentID        string `json:"agent_id"`
		Intent         string `json:"intent"`
		MaxSuggestions int32  `json:"max_suggestions"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.SessionID == "" || req.AgentID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "session_id 与 agent_id 不能为空"})
		return
	}
	resp, err := h.session.SuggestReplies(tenant.ClientContext(c), &sessionv1.SuggestRepliesRequest{
		RequestId:      uuid.NewString(),
		SessionId:      req.SessionID,
		AgentId:        req.AgentID,
		Intent:         req.Intent,
		MaxSuggestions: req.MaxSuggestions,
	})
	if err != nil {
		c.JSON(httpx.StatusFromGRPC(err), gin.H{"error": "获取回复建议失败"})
		return
	}
	c.JSON(http.StatusOK, toSuggestView(resp))
}

// CreateTicket 一键建单。
// 路由：POST /api/v1/agent/tickets
//
// 租户来源（M5-5.5）：以可信上下文租户为准；请求体 tenant_id 不一致即视为伪造（403）。
func (h *AgentHandler) CreateTicket(c *gin.Context) {
	var req struct {
		TenantID    string `json:"tenant_id"`
		SessionID   string `json:"session_id"`
		UserID      string `json:"user_id"`
		AgentID     string `json:"agent_id"`
		Title       string `json:"title"`
		Description string `json:"description"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "非法请求体"})
		return
	}
	identity, ok := tenant.FromGin(c)
	if !ok || !identity.HasTenant() {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "缺少租户上下文"})
		return
	}
	if req.TenantID != "" && req.TenantID != identity.TenantID {
		log.Printf("[tenant-isolation-denied] 建单租户伪造已拒绝 tenant=%s requested=%s session=%s",
			identity.TenantID, req.TenantID, req.SessionID)
		c.JSON(http.StatusForbidden, gin.H{"error": "请求租户与身份租户不一致"})
		return
	}
	if req.SessionID == "" || req.AgentID == "" || req.Title == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "session_id/agent_id/title 不能为空"})
		return
	}
	ticket, err := h.ticket.CreateTicket(tenant.ClientContext(c), identity.TenantID, req.SessionID,
		req.UserID, req.AgentID, req.Title, req.Description)
	if err != nil {
		c.JSON(httpx.StatusFromGRPC(err), gin.H{"error": "建单失败"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ticket": toTicketView(ticket)})
}

// ListTickets 工单列表。
// 路由：GET /api/v1/agent/tickets?agent_id=&status=CREATED|PROCESSING|CLOSED
func (h *AgentHandler) ListTickets(c *gin.Context) {
	agentID := c.Query("agent_id")
	statusStr := c.Query("status")
	var status ticketv1.TicketStatus
	if statusStr != "" {
		parsed, ok := parseTicketStatus(statusStr)
		if !ok {
			c.JSON(http.StatusBadRequest, gin.H{"error": "非法状态: " + statusStr})
			return
		}
		status = parsed
	}
	tickets, err := h.ticket.ListTickets(tenant.ClientContext(c), agentID, status)
	if err != nil {
		c.JSON(httpx.StatusFromGRPC(err), gin.H{"error": "获取工单失败"})
		return
	}
	views := make([]ticketView, 0, len(tickets))
	for _, t := range tickets {
		views = append(views, toTicketView(t))
	}
	c.JSON(http.StatusOK, gin.H{"tickets": views})
}

// UpdateTicketStatus 工单状态流转。
// 路由：POST /api/v1/agent/tickets/:ticket_id/status {status, operator}
func (h *AgentHandler) UpdateTicketStatus(c *gin.Context) {
	ticketID := c.Param("ticket_id")
	var req struct {
		Status   string `json:"status"`
		Operator string `json:"operator"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "非法请求体"})
		return
	}
	status, ok := parseTicketStatus(req.Status)
	if !ok {
		c.JSON(http.StatusBadRequest, gin.H{"error": "非法状态: " + req.Status})
		return
	}
	resp, err := h.ticket.UpdateTicketStatus(tenant.ClientContext(c), ticketID, status, req.Operator)
	if err != nil {
		c.JSON(httpx.StatusFromGRPC(err), gin.H{"error": "工单流转失败"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": resp.Ok, "reason": resp.Reason, "ticket": toTicketView(resp.Ticket)})
}

// toSessionSummaries proto 会话摘要 → JSON 视图。
func toSessionSummaries(in []*sessionv1.SessionSummary) []sessionSummary {
	out := make([]sessionSummary, 0, len(in))
	for _, s := range in {
		out = append(out, sessionSummary{
			SessionID: s.SessionId, UserID: s.UserId, State: s.State.String(),
			AgentID: s.AgentId, CreatedAtMs: s.CreatedAtMs,
			UpdatedAtMs: s.UpdatedAtMs, MessageCount: s.MessageCount, Summary: s.Summary,
		})
	}
	return out
}

// toSuggestView proto 建议响应 → JSON 视图（纯函数，便于单测）。
func toSuggestView(resp *sessionv1.SuggestRepliesResponse) suggestView {
	return suggestView{
		RequestID:     resp.RequestId,
		Ok:            resp.Ok,
		Degraded:      resp.Degraded,
		DegradeReason: resp.DegradeReason,
		Queued:        resp.Queued,
		Suggestions:   resp.Suggestions,
		ElapsedMs:     resp.ElapsedMs,
	}
}

// toTicketView proto 工单 → JSON 视图。
func toTicketView(t *ticketv1.Ticket) ticketView {
	return ticketView{
		TicketID: t.TicketId, TenantID: t.TenantId, SessionID: t.SessionId,
		UserID: t.UserId, AgentID: t.AgentId, Title: t.Title,
		Description: t.Description, Status: t.Status.String(),
		CreatedAtMs: t.CreatedAtMs, UpdatedAtMs: t.UpdatedAtMs,
	}
}

// parseTicketStatus 状态字符串 → proto 枚举。
func parseTicketStatus(s string) (ticketv1.TicketStatus, bool) {
	switch s {
	case "CREATED":
		return ticketv1.TicketStatus_CREATED, true
	case "PROCESSING":
		return ticketv1.TicketStatus_PROCESSING, true
	case "CLOSED":
		return ticketv1.TicketStatus_CLOSED, true
	default:
		return ticketv1.TicketStatus_TICKET_STATUS_UNSPECIFIED, false
	}
}
