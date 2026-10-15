package ws

import (
	"encoding/json"
	"log"
	"sync"
	"time"

	"github.com/gorilla/websocket"

	sessionv1 "github.com/lingxi/gateway-go/proto/sessionv1"
)

// 心跳与缓冲参数。
const (
	// 断连缓冲窗口 30s：连接断开后消息保留时长（客户端可在此窗口内重连续传）。
	// 实际以环形缓冲条数 bufferMax 为准，时间维度由客户端重连策略保证。
	// bufferWindow = 30 * time.Second
	// 断连缓冲条数上限，防止长会话内存膨胀。
	bufferMax = 200
	// 心跳间隔：服务端主动 ping 的周期。
	pingInterval = 30 * time.Second
	// 等待 pong 的容忍时长，超过即判定断连。
	pongWait = 60 * time.Second
	// 写缓冲容量。
	writeBufferSize = 512
	// 单条消息大小上限（4KB，与 Java 域一致）。
	maxMessageBytes = 8 * 1024
)

// SessionClient 会话域 gRPC 调用接口（由 handler 注入，便于单测替换）。
type SessionClient interface {
	CreateSession(userID, channel, tenantID string) (sessionID string, err error)
	SendMessage(sessionID, sender, senderID, content string) (*sessionv1.SendMessageResponse, error)
	PullMessages(sessionID string, lastSeq int64, limit int32) ([]*sessionv1.SessionMessage, error)
}

// Client 单个 WSS 连接：管理读写双 goroutine 与断连缓冲。
type Client struct {
	sessionID string
	conn      *websocket.Conn
	hub       *Hub
	session   SessionClient
	// role 连接角色（user / agent）：用于下行差异化投递（如建议结果仅推坐席端）。
	role string

	// 断连缓冲：近期消息（seq → 内容），供重连续传。
	bufferMu sync.Mutex
	buffer   []*sessionv1.SessionMessage
	lastSeq  int64 // 会话已确认的最高 seq（用户已 ack 或已发送）

	// 下行写通道（缓冲满则断开，防慢客户端拖垮网关）。
	send chan *Envelope
}

// NewClient 创建连接对象并注册到 Hub（缺省角色 user，保持既有调用方兼容）。
func NewClient(sessionID string, conn *websocket.Conn, hub *Hub, session SessionClient) *Client {
	return NewClientWithRole(sessionID, conn, hub, session, "user")
}

// NewClientWithRole 创建带角色的连接对象并注册到 Hub。
func NewClientWithRole(sessionID string, conn *websocket.Conn, hub *Hub, session SessionClient, role string) *Client {
	c := &Client{
		sessionID: sessionID,
		conn:      conn,
		hub:       hub,
		session:   session,
		role:      role,
		buffer:    make([]*sessionv1.SessionMessage, 0, bufferMax),
		send:      make(chan *Envelope, writeBufferSize),
	}
	hub.Register(c)
	return c
}

// Role 返回连接角色（user / agent），供角色化广播筛选。
func (c *Client) Role() string {
	return c.role
}

// Run 启动读写循环（阻塞直到连接关闭）。
func (c *Client) Run() {
	defer func() {
		c.hub.Unregister(c)
		_ = c.conn.Close()
	}()

	// 写 goroutine：下行消息 + 周期心跳。
	go c.writeLoop()

	// 读循环：处理上行消息。
	c.readLoop()
}

// deliver 将下行消息写入通道（非阻塞，通道满则关闭连接）。
func (c *Client) deliver(env *Envelope) {
	select {
	case c.send <- env:
	default:
		// 写通道已满：客户端消费过慢，断开以保护网关。
		log.Printf("ws: 会话 %s 写通道已满，强制断开", c.sessionID)
		_ = c.conn.Close()
	}
}

// writeLoop 负责心跳 ping 与下行消息发送。
func (c *Client) writeLoop() {
	ticker := time.NewTicker(pingInterval)
	defer ticker.Stop()

	for {
		select {
		case env, ok := <-c.send:
			if !ok {
				return
			}
			_ = c.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := c.conn.WriteJSON(env); err != nil {
				log.Printf("ws: 会话 %s 下行发送失败: %v", c.sessionID, err)
				return
			}
		case <-ticker.C:
			_ = c.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := c.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

// readLoop 处理上行消息：ping、send、resume。
func (c *Client) readLoop() {
	_ = c.conn.SetReadDeadline(time.Now().Add(pongWait))
	c.conn.SetReadLimit(maxMessageBytes)
	c.conn.SetPongHandler(func(string) error {
		return c.conn.SetReadDeadline(time.Now().Add(pongWait))
	})

	for {
		_, data, err := c.conn.ReadMessage()
		if err != nil {
			return
		}
		env, perr := parseEnvelope(data)
		if perr != nil {
			c.deliver(&Envelope{Type: MsgTypeError, Error: "非法消息格式"})
			continue
		}
		switch env.Type {
		case MsgTypePing:
			c.deliver(&Envelope{Type: MsgTypePong})
		case MsgTypeSend:
			c.handleSend(env)
		case MsgTypeResume:
			c.handleResume(env)
		default:
			c.deliver(&Envelope{Type: MsgTypeError, Error: "未知消息类型"})
		}
	}
}

// handleSend 用户上行消息：转交 Java 会话域落库，ack 携带 seq。
func (c *Client) handleSend(env *Envelope) {
	if env.Content == "" {
		c.deliver(&Envelope{Type: MsgTypeError, Error: "消息内容不能为空"})
		return
	}
	resp, err := c.session.SendMessage(c.sessionID, "user", env.Sender, env.Content)
	if err != nil {
		log.Printf("ws: 会话 %s 消息转发失败: %v", c.sessionID, err)
		c.deliver(&Envelope{Type: MsgTypeError, Error: "消息发送失败，请重试"})
		return
	}
	// 写入断连缓冲，供后续重连续传。
	saved := &sessionv1.SessionMessage{
		SessionId: c.sessionID, Sender: "user", Content: env.Content,
		Seq: resp.Seq, MessageId: resp.MessageId,
	}
	c.bufferAppend(saved)
	// 下发给同会话的其他连接（坐席工作台观察端）；排除发送者避免自身消息重复回显。
	c.hub.BroadcastExcept(c.sessionID, c, toChatEnvelope(saved))
	c.deliver(&Envelope{Type: MsgTypeAck, Seq: resp.Seq, MessageID: resp.MessageId})
}

// handleResume 断连重连：按 lastSeq 补齐缓冲内的遗漏消息。
func (c *Client) handleResume(env *Envelope) {
	// 先尝试从本地缓冲补齐，再回源 Java 域（跨网关实例场景）。
	missed := c.bufferAfter(env.LastSeq)
	if len(missed) == 0 {
		if msgs, err := c.session.PullMessages(c.sessionID, env.LastSeq, 50); err == nil {
			for _, m := range msgs {
				c.bufferAppend(m)
				c.deliver(toChatEnvelope(m))
			}
			return
		}
	}
	for _, m := range missed {
		c.deliver(toChatEnvelope(m))
	}
}

// bufferAppend 写入断连缓冲（环形，超过上限丢弃最旧）。
func (c *Client) bufferAppend(m *sessionv1.SessionMessage) {
	c.bufferMu.Lock()
	defer c.bufferMu.Unlock()
	if len(c.buffer) >= bufferMax {
		c.buffer = c.buffer[1:]
	}
	c.buffer = append(c.buffer, m)
	if m.Seq > c.lastSeq {
		c.lastSeq = m.Seq
	}
}

// bufferAfter 返回缓冲中 seq 大于 lastSeq 的消息（保持顺序）。
func (c *Client) bufferAfter(lastSeq int64) []*sessionv1.SessionMessage {
	c.bufferMu.Lock()
	defer c.bufferMu.Unlock()
	out := make([]*sessionv1.SessionMessage, 0)
	for _, m := range c.buffer {
		if m.Seq > lastSeq {
			out = append(out, m)
		}
	}
	return out
}

// toChatEnvelope 会话消息 → 网关下行信封。
func toChatEnvelope(m *sessionv1.SessionMessage) *Envelope {
	return &Envelope{
		Type:      MsgTypeChat,
		SessionID: m.SessionId,
		Sender:    m.Sender,
		Content:   m.Content,
		Seq:       m.Seq,
		MessageID: m.MessageId,
	}
}

// MarshalJSON 供调试打印使用（可选）。
func (e *Envelope) MarshalJSON() ([]byte, error) {
	return json.Marshal(*e)
}
