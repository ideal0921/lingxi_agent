package handler

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"

	"github.com/lingxi/gateway-go/internal/client"
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
	sessions, err := h.session.ListWaitingSessions(limit)
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "获取客服队列失败"})
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
	resp, err := h.session.TakeOver(req.SessionID, req.AgentID)
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "接管请求失败"})
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
	sessions, err := h.session.ListAgentSessions(agentID)
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "获取坐席会话失败"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"sessions": toSessionSummaries(sessions)})
}

// CreateTicket 一键建单。
// 路由：POST /api/v1/agent/tickets
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
	if req.TenantID == "" || req.SessionID == "" || req.AgentID == "" || req.Title == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "tenant_id/session_id/agent_id/title 不能为空"})
		return
	}
	ticket, err := h.ticket.CreateTicket(req.TenantID, req.SessionID, req.UserID,
		req.AgentID, req.Title, req.Description)
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "建单失败"})
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
	tickets, err := h.ticket.ListTickets(agentID, status)
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "获取工单失败"})
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
	resp, err := h.ticket.UpdateTicketStatus(ticketID, status, req.Operator)
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "工单流转失败"})
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
			UpdatedAtMs: s.UpdatedAtMs, MessageCount: s.MessageCount,
		})
	}
	return out
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
