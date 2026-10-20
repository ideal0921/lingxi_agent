package ws

import (
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"

	"github.com/lingxi/gateway-go/internal/httpx"
	"github.com/lingxi/gateway-go/internal/tenant"
	"github.com/lingxi/gateway-go/internal/trace"
)

// upgrader WSS 升级器：允许跨域（前端开发环境）。
var upgrader = websocket.Upgrader{
	ReadBufferSize:  4096,
	WriteBufferSize: 4096,
	CheckOrigin: func(r *http.Request) bool {
		// M2 阶段放开跨域，生产环境收紧为白名单。
		return true
	},
}

// Handler WSS 网关处理器：依赖注入 Hub 与会话域客户端。
//
// M5-5.5：租户不再由请求体/默认值决定，一律取鉴权中间件注入的可信身份，
// 长连接建立时固化，作为该连接全部出站调用的租户来源。
type Handler struct {
	hub     *Hub
	session SessionClient
	// recorder span 记录器（M5-5.9）：按「每条消息收发」记 WSS span，可为 nil。
	recorder trace.Recorder
}

// NewHandler 创建 WSS 处理器。
func NewHandler(hub *Hub, session SessionClient) *Handler {
	return &Handler{hub: hub, session: session}
}

// SetRecorder 注入 span 记录器（M5-5.9）；未注入时 WSS 不落链路追踪。
func (h *Handler) SetRecorder(recorder trace.Recorder) {
	h.recorder = recorder
}

// ServeWS 升级 HTTP 连接为 WSS 并接管会话长连接。
// 路由：GET /ws?session_id=xxx&role=agent（role 缺省为 user）
func (h *Handler) ServeWS(c *gin.Context) {
	sessionID := c.Query("session_id")
	if sessionID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "缺少 session_id 参数"})
		return
	}
	role := c.Query("role")
	if role == "" {
		role = "user"
	}
	identity, ok := tenant.FromGin(c)
	if !ok || !identity.HasTenant() {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "缺少租户上下文，拒绝建立长连接"})
		return
	}

	conn, err := upgrader.Upgrade(c.Writer, c.Request, nil)
	if err != nil {
		log.Printf("ws: 升级失败 session=%s err=%v", sessionID, err)
		return
	}

	client := NewClientWithIdentity(sessionID, conn, h.hub, h.session, role, identity)
	// 链路 ID（M5-5.9）：WSS 握手不在 HTTP span 范围（/ws 不记 span），
	// 故按入站头复用或生成一个连接级链路 ID，供后续每条消息 span 与出站 metadata 使用。
	client.SetTraceID(resolveTraceID(c))
	client.SetRecorder(h.recorder)
	log.Printf("ws: 会话 %s 租户 %s 角色 %s 已连接", sessionID, identity.TenantID, role)
	client.Run()
}

// resolveTraceID 取链路 ID：优先入站 `x-trace-id`，缺省生成。
func resolveTraceID(c *gin.Context) string {
	if id := strings.TrimSpace(c.GetHeader(trace.HeaderTraceID)); id != "" {
		return id
	}
	return trace.ID()
}

// CreateSession 用户端建会话入口：转发 Java 会话域，返回 session_id 供 WSS 连接。
// 路由：POST /api/v1/session {user_id, channel, tenant_id?}
//
// 租户来源（M5-5.5）：**一律以可信上下文租户为准**；请求体 tenant_id 仅作兼容字段，
// 与上下文不一致即视为伪造，返回 403 并记录告警（不再静默采信请求体）。
func (h *Handler) CreateSession(c *gin.Context) {
	var req struct {
		UserID   string `json:"user_id"`
		Channel  string `json:"channel"`
		TenantID string `json:"tenant_id"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "非法请求体"})
		return
	}
	if req.UserID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "user_id 不能为空"})
		return
	}
	if req.Channel == "" {
		req.Channel = "web"
	}
	identity, ok := tenant.FromGin(c)
	if !ok || !identity.HasTenant() {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "缺少租户上下文"})
		return
	}
	if req.TenantID != "" && req.TenantID != identity.TenantID {
		log.Printf("[tenant-isolation-denied] 建会话租户伪造已拒绝 tenant=%s requested=%s user=%s",
			identity.TenantID, req.TenantID, req.UserID)
		c.JSON(http.StatusForbidden, gin.H{"error": "请求租户与身份租户不一致"})
		return
	}

	sessionID, err := h.session.CreateSession(tenant.ClientContext(c), req.UserID, req.Channel, identity.TenantID)
	if err != nil {
		log.Printf("create-session: 创建失败 user=%s err=%v", req.UserID, err)
		c.JSON(httpx.StatusFromGRPC(err), gin.H{"error": "会话创建失败"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"session_id": sessionID})
}

// ListSessions 用户端历史会话列表。
// 路由：GET /api/v1/sessions?limit=20
//
// 租户与用户一律取鉴权中间件注入的**可信身份**（M5-5.5）：不接受前端传 user_id/tenant_id，
// 否则可借参数枚举他人会话。limit 收敛到 [1,50]，非法值回落缺省 20。
func (h *Handler) ListSessions(c *gin.Context) {
	identity, ok := tenant.FromGin(c)
	if !ok || !identity.HasTenant() {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "缺少租户上下文"})
		return
	}

	items, err := h.session.ListSessionsByUser(tenant.ClientContext(c), clampSessionLimit(c.Query("limit")))
	if err != nil {
		log.Printf("list-sessions: 查询失败 tenant=%s user=%s err=%v",
			identity.TenantID, identity.UserID, err)
		c.JSON(httpx.StatusFromGRPC(err), gin.H{"error": "会话列表查询失败"})
		return
	}

	// 状态枚举转可读名：前端按 AI_ACTIVE / WAITING_HUMAN 等字符串渲染标签，
	// 直接下发枚举数值会让前端无从判断（protobuf JSON 默认也是名字，这里与之一致）。
	sessions := make([]gin.H, 0, len(items))
	for _, item := range items {
		sessions = append(sessions, gin.H{
			"session_id":        item.SessionId,
			"user_id":           item.UserId,
			"channel":           item.Channel,
			"state":             item.State.String(),
			"created_at_ms":     item.CreatedAtMs,
			"last_active_at_ms": item.LastActiveAtMs,
			"message_count":     item.MessageCount,
			"last_preview":      item.LastPreview,
		})
	}
	c.JSON(http.StatusOK, gin.H{"sessions": sessions})
}

// clampSessionLimit 会话列表条数收敛：缺省 20、上限 50，非法/越界回落默认值。
func clampSessionLimit(raw string) int32 {
	const def, max = 20, 50
	if raw == "" {
		return def
	}
	n, err := strconv.Atoi(raw)
	if err != nil || n <= 0 {
		return def
	}
	if n > max {
		return max
	}
	return int32(n)
}

// PushAgentReply 坐席回复接口：经网关下行推送给用户。
// 路由：POST /api/v1/agent/reply {session_id, content, agent_id}
func (h *Handler) PushAgentReply(c *gin.Context) {
	var req struct {
		SessionID string `json:"session_id"`
		Content   string `json:"content"`
		AgentID   string `json:"agent_id"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "非法请求体"})
		return
	}
	if req.SessionID == "" || req.Content == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "session_id 与 content 不能为空"})
		return
	}

	// 落库到 Java 会话域，获得 seq（Java 侧校验会话归属当前租户）。
	resp, err := h.session.SendMessage(tenant.ClientContext(c), req.SessionID, "agent", req.AgentID, req.Content)
	if err != nil {
		log.Printf("agent-reply: 落库失败 session=%s err=%v", req.SessionID, err)
		c.JSON(httpx.StatusFromGRPC(err), gin.H{"error": "消息落库失败"})
		return
	}

	// 下行广播给用户连接。
	h.hub.Broadcast(req.SessionID, &Envelope{
		Type:      MsgTypeChat,
		SessionID: req.SessionID,
		Sender:    "agent",
		Content:   req.Content,
		Seq:       resp.Seq,
		MessageID: resp.MessageId,
		Timestamp: time.Now().UnixMilli(),
	})

	c.JSON(http.StatusOK, gin.H{"seq": resp.Seq, "message_id": resp.MessageId})
}
