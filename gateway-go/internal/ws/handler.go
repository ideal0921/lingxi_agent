package ws

import (
	"log"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"
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
type Handler struct {
	hub     *Hub
	session SessionClient
}

// NewHandler 创建 WSS 处理器。
func NewHandler(hub *Hub, session SessionClient) *Handler {
	return &Handler{hub: hub, session: session}
}

// ServeWS 升级 HTTP 连接为 WSS 并接管会话长连接。
// 路由：GET /ws?session_id=xxx
func (h *Handler) ServeWS(c *gin.Context) {
	sessionID := c.Query("session_id")
	if sessionID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "缺少 session_id 参数"})
		return
	}

	conn, err := upgrader.Upgrade(c.Writer, c.Request, nil)
	if err != nil {
		log.Printf("ws: 升级失败 session=%s err=%v", sessionID, err)
		return
	}

	client := NewClient(sessionID, conn, h.hub, h.session)
	log.Printf("ws: 会话 %s 已连接", sessionID)
	client.Run()
}

// CreateSession 用户端建会话入口：转发 Java 会话域，返回 session_id 供 WSS 连接。
// 路由：POST /api/v1/session {user_id, channel}
func (h *Handler) CreateSession(c *gin.Context) {
	var req struct {
		UserID  string `json:"user_id"`
		Channel string `json:"channel"`
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

	sessionID, err := h.session.CreateSession(req.UserID, req.Channel)
	if err != nil {
		log.Printf("create-session: 创建失败 user=%s err=%v", req.UserID, err)
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "会话创建失败"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"session_id": sessionID})
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

	// 落库到 Java 会话域，获得 seq。
	resp, err := h.session.SendMessage(req.SessionID, "agent", req.AgentID, req.Content)
	if err != nil {
		log.Printf("agent-reply: 落库失败 session=%s err=%v", req.SessionID, err)
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "消息落库失败"})
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
