package ws

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	"google.golang.org/grpc/metadata"

	"github.com/lingxi/gateway-go/internal/tenant"
	sessionv1 "github.com/lingxi/gateway-go/proto/sessionv1"
)

// errForTest 通用测试错误。
var errForTest = errors.New("测试错误")

// mockSessionClient 会话域 gRPC 客户端测试替身。
type mockSessionClient struct {
	createFn func(userID, channel, tenantID string) (string, error)
	sendFn   func(sessionID, sender, senderID, content string) (*sessionv1.SendMessageResponse, error)
	pullFn   func(sessionID string, lastSeq int64, limit int32) ([]*sessionv1.SessionMessage, error)
	listFn   func(limit int32) ([]*sessionv1.SessionListItem, error)
}

func (m *mockSessionClient) ListSessionsByUser(_ context.Context, limit int32) ([]*sessionv1.SessionListItem, error) {
	if m.listFn == nil {
		return nil, nil
	}
	return m.listFn(limit)
}

func (m *mockSessionClient) CreateSession(ctx context.Context, userID, channel, tenantID string) (string, error) {
	return m.createFn(userID, channel, tenantID)
}

func (m *mockSessionClient) SendMessage(ctx context.Context, sessionID, sender, senderID, content string) (*sessionv1.SendMessageResponse, error) {
	return m.sendFn(sessionID, sender, senderID, content)
}

func (m *mockSessionClient) PullMessages(ctx context.Context, sessionID string, lastSeq int64, limit int32) ([]*sessionv1.SessionMessage, error) {
	return m.pullFn(sessionID, lastSeq, limit)
}

// withTestTenant 注入测试用可信租户身份，模拟鉴权中间件（M5-5.5）。
func withTestTenant(tenantID string) gin.HandlerFunc {
	return func(c *gin.Context) {
		tenant.SetToGin(c, tenant.Identity{TenantID: tenantID, UserID: "u1", Role: "user"})
		c.Next()
	}
}

// newTestClient 构造测试用 Client（不启动读写循环，仅测纯逻辑）。
func newTestClient(t *testing.T, hub *Hub, mock *mockSessionClient) *Client {
	t.Helper()
	if hub == nil {
		hub = NewHub()
	}
	c := NewClient("s1", nil, hub, mock)
	t.Cleanup(func() { hub.Unregister(c) })
	return c
}

func TestParseEnvelope(t *testing.T) {
	t.Run("合法 JSON", func(t *testing.T) {
		env, err := parseEnvelope([]byte(`{"type":"send","content":"你好","sender":"u1"}`))
		if err != nil {
			t.Fatalf("parseEnvelope 应成功: %v", err)
		}
		if env.Type != MsgTypeSend || env.Content != "你好" || env.Sender != "u1" {
			t.Fatalf("解析结果不符: %+v", env)
		}
	})

	t.Run("非法 JSON", func(t *testing.T) {
		if _, err := parseEnvelope([]byte(`{"type":`)); err == nil {
			t.Fatal("非法 JSON 应返回错误")
		}
	})
}

func TestHubRegisterBroadcastUnregister(t *testing.T) {
	hub := NewHub()
	mock := &mockSessionClient{}

	c1 := NewClient("s1", nil, hub, mock)
	_ = NewClient("s2", nil, hub, mock)

	if hub.Count("s1") != 1 || hub.Count("s2") != 1 {
		t.Fatal("注册后各会话应各有 1 个连接")
	}

	// 同会话多连接：用户端与坐席工作台观察端并存，不互相顶替。
	c1b := NewClient("s1", nil, hub, mock)
	if hub.Count("s1") != 2 {
		t.Fatalf("同会话应支持多连接，实际 %d", hub.Count("s1"))
	}

	// 广播下发到会话全部连接。
	hub.Broadcast("s1", &Envelope{Type: MsgTypeChat, Seq: 1})
	if got := <-c1.send; got.Seq != 1 {
		t.Fatalf("c1 应收到广播: %+v", got)
	}
	if got := <-c1b.send; got.Seq != 1 {
		t.Fatalf("c1b 应收到广播: %+v", got)
	}

	// 排除指定连接：仅其余连接收到。
	hub.BroadcastExcept("s1", c1, &Envelope{Type: MsgTypeChat, Seq: 2})
	if got := <-c1b.send; got.Seq != 2 {
		t.Fatalf("c1b 应收到排除广播: %+v", got)
	}
	select {
	case got := <-c1.send:
		t.Fatalf("被排除的连接不应收到消息: %+v", got)
	default:
	}

	// 逐连接注销，最后一个移除后索引清理。
	hub.Unregister(c1)
	if hub.Count("s1") != 1 {
		t.Fatalf("注销 c1 后应剩 1 个连接，实际 %d", hub.Count("s1"))
	}
	hub.Unregister(c1b)
	if hub.Count("s1") != 0 {
		t.Fatal("注销全部连接后会话索引应清理")
	}

	// 对未注册会话广播应无副作用（不 panic）。
	hub.Broadcast("unknown", &Envelope{Type: MsgTypeChat})
	if hub.Count("s2") != 1 {
		t.Fatal("s2 不应受影响")
	}
}

func TestHubBroadcastToRole(t *testing.T) {
	hub := NewHub()
	mock := &mockSessionClient{}

	// 同一会话：用户端 + 坐席端两个连接（角色不同）。
	user := NewClientWithRole("s1", nil, hub, mock, "user")
	agent := NewClientWithRole("s1", nil, hub, mock, "agent")
	t.Cleanup(func() { hub.Unregister(user); hub.Unregister(agent) })

	if user.Role() != "user" || agent.Role() != "agent" {
		t.Fatalf("角色访问器不符: user=%s agent=%s", user.Role(), agent.Role())
	}

	// 向坐席端广播：仅 agent 连接收到，user 连接绝不收到（防 AI 草稿泄漏给用户）。
	hub.BroadcastToRole("s1", "agent", &Envelope{Type: MsgTypeSuggest, Suggestions: []string{"候选"}})

	got := <-agent.send
	if got.Type != MsgTypeSuggest || len(got.Suggestions) != 1 || got.Suggestions[0] != "候选" {
		t.Fatalf("坐席端应收到建议信封: %+v", got)
	}
	select {
	case leak := <-user.send:
		t.Fatalf("用户端不应收到建议（防泄漏）: %+v", leak)
	default:
	}

	// 向用户端广播：仅 user 连接收到。
	hub.BroadcastToRole("s1", "user", &Envelope{Type: MsgTypeChat, Seq: 1})
	if got := <-user.send; got.Seq != 1 {
		t.Fatalf("用户端应收到广播: %+v", got)
	}
	select {
	case extra := <-agent.send:
		t.Fatalf("坐席端不应收到用户端广播: %+v", extra)
	default:
	}

	// 未注册会话广播应无副作用（不 panic）。
	hub.BroadcastToRole("unknown", "agent", &Envelope{Type: MsgTypeSuggest})
}

// TestCallContextCarriesTraceID WSS 出站 gRPC context 携带租户与链路 ID（M5-5.9）。
func TestCallContextCarriesTraceID(t *testing.T) {
	hub := NewHub()
	c := NewClientWithIdentity("s1", nil, hub, &mockSessionClient{}, "agent",
		tenant.Identity{TenantID: "t1", UserID: "u1", Role: "agent"})
	t.Cleanup(func() { hub.Unregister(c) })
	c.SetTraceID("conn-trace")

	md, ok := metadata.FromOutgoingContext(c.callContext(c.messageTraceID(&Envelope{TraceID: "msg-trace"})))
	if !ok {
		t.Fatal("应注入出站 metadata")
	}
	if got := md.Get(tenant.MetadataTraceID); len(got) != 1 || got[0] != "msg-trace" {
		t.Fatalf("消息级链路 ID 应优先于连接级: %v", got)
	}
	if got := md.Get(tenant.MetadataTenantID); len(got) != 1 || got[0] != "t1" {
		t.Fatalf("租户 metadata 应保留: %v", got)
	}

	// 信封未携带 trace_id 时回落连接级。
	md2, _ := metadata.FromOutgoingContext(c.callContext(c.messageTraceID(&Envelope{})))
	if got := md2.Get(tenant.MetadataTraceID); len(got) != 1 || got[0] != "conn-trace" {
		t.Fatalf("应回落连接级链路 ID: %v", got)
	}
}

func TestBufferAppendAndAfter(t *testing.T) {
	hub := NewHub()
	c := newTestClient(t, hub, &mockSessionClient{})

	// 顺序写入 1..bufferMax，验证 lastSeq 推进。
	for i := int64(1); i <= bufferMax; i++ {
		c.bufferAppend(&sessionv1.SessionMessage{Seq: i, Content: "msg"})
	}
	if c.lastSeq != bufferMax {
		t.Fatalf("lastSeq 应推进到 %d，实际 %d", bufferMax, c.lastSeq)
	}

	// seq 续传：lastSeq=5 应补齐 6..bufferMax。
	missed := c.bufferAfter(5)
	if len(missed) != int(bufferMax-5) || missed[0].Seq != 6 {
		t.Fatalf("续传补齐数量不符: 剩余 %d 条", len(missed))
	}

	// 无遗漏场景。
	if got := c.bufferAfter(bufferMax); len(got) != 0 {
		t.Fatalf("lastSeq 为最新时不应有遗漏: %v", got)
	}

	// 环形淘汰：继续写入应丢弃最旧消息（seq=1）。
	c.bufferAppend(&sessionv1.SessionMessage{Seq: bufferMax + 1, Content: "msg"})
	after := c.bufferAfter(bufferMax)
	if len(after) != 1 || after[0].Seq != bufferMax+1 {
		t.Fatalf("环形缓冲应淘汰最旧，实际剩余 %v", after)
	}
}

func TestHandleSend(t *testing.T) {
	t.Run("正常发送返回 ack 并进入缓冲", func(t *testing.T) {
		hub := NewHub()
		mock := &mockSessionClient{
			sendFn: func(sessionID, sender, senderID, content string) (*sessionv1.SendMessageResponse, error) {
				return &sessionv1.SendMessageResponse{Seq: 3, MessageId: "m3"}, nil
			},
		}
		c := newTestClient(t, hub, mock)

		c.handleSend(&Envelope{Type: MsgTypeSend, Sender: "u1", Content: "你好"})

		got := <-c.send
		if got.Type != MsgTypeAck || got.Seq != 3 || got.MessageID != "m3" {
			t.Fatalf("ack 信封不符: %+v", got)
		}
		if c.lastSeq != 3 {
			t.Fatalf("lastSeq 应更新为 3，实际 %d", c.lastSeq)
		}
		if len(c.bufferAfter(2)) != 1 {
			t.Fatal("消息应写入断连缓冲")
		}
	})

	t.Run("空内容拒绝", func(t *testing.T) {
		c := newTestClient(t, NewHub(), &mockSessionClient{})
		c.handleSend(&Envelope{Type: MsgTypeSend, Content: ""})
		got := <-c.send
		if got.Type != MsgTypeError {
			t.Fatalf("空内容应返回错误信封: %+v", got)
		}
	})

	t.Run("转发失败返回错误", func(t *testing.T) {
		hub := NewHub()
		mock := &mockSessionClient{
			sendFn: func(sessionID, sender, senderID, content string) (*sessionv1.SendMessageResponse, error) {
				return nil, errForTest
			},
		}
		c := newTestClient(t, hub, mock)
		c.handleSend(&Envelope{Type: MsgTypeSend, Sender: "u1", Content: "hi"})
		got := <-c.send
		if got.Type != MsgTypeError {
			t.Fatalf("转发失败应返回错误信封: %+v", got)
		}
	})

	t.Run("用户消息广播给同会话其他连接并排除发送者", func(t *testing.T) {
		hub := NewHub()
		mock := &mockSessionClient{
			sendFn: func(sessionID, sender, senderID, content string) (*sessionv1.SendMessageResponse, error) {
				return &sessionv1.SendMessageResponse{Seq: 5, MessageId: "m5"}, nil
			},
		}
		sender := newTestClient(t, hub, mock)                   // 用户端
		observer := NewClient(sender.sessionID, nil, hub, mock) // 坐席工作台观察端
		t.Cleanup(func() { hub.Unregister(observer) })

		sender.handleSend(&Envelope{Type: MsgTypeSend, Sender: "u1", Content: "请问订单"})

		// 观察端收到用户消息的 chat 下行。
		got := <-observer.send
		if got.Type != MsgTypeChat || got.Sender != "user" || got.Content != "请问订单" || got.Seq != 5 {
			t.Fatalf("观察端应收到用户消息: %+v", got)
		}
		// 发送者只收到 ack，不回显自己的消息。
		if ack := <-sender.send; ack.Type != MsgTypeAck {
			t.Fatalf("发送者应收到 ack: %+v", ack)
		}
		select {
		case dup := <-sender.send:
			t.Fatalf("发送者不应回显自身消息: %+v", dup)
		default:
		}
	})
}

func TestHandleResume(t *testing.T) {
	t.Run("本地缓冲补齐", func(t *testing.T) {
		hub := NewHub()
		c := newTestClient(t, hub, &mockSessionClient{})
		c.bufferAppend(&sessionv1.SessionMessage{Seq: 1, Content: "a"})
		c.bufferAppend(&sessionv1.SessionMessage{Seq: 2, Content: "b"})

		c.handleResume(&Envelope{Type: MsgTypeResume, LastSeq: 0})

		got1 := <-c.send
		got2 := <-c.send
		if got1.Type != MsgTypeChat || got1.Seq != 1 || got2.Seq != 2 {
			t.Fatalf("本地续传内容不符: %+v / %+v", got1, got2)
		}
	})

	t.Run("本地无遗漏时回源 Java 域", func(t *testing.T) {
		hub := NewHub()
		pulled := false
		mock := &mockSessionClient{
			pullFn: func(sessionID string, lastSeq int64, limit int32) ([]*sessionv1.SessionMessage, error) {
				pulled = true
				return []*sessionv1.SessionMessage{
					{Seq: 10, Content: "agent 回复"},
				}, nil
			},
		}
		c := newTestClient(t, hub, mock)

		c.handleResume(&Envelope{Type: MsgTypeResume, LastSeq: 9})

		if !pulled {
			t.Fatal("本地无遗漏时应回源 Java 域")
		}
		got := <-c.send
		if got.Type != MsgTypeChat || got.Seq != 10 {
			t.Fatalf("回源消息信封不符: %+v", got)
		}
	})
}

// ListSessions：历史会话列表——身份取可信上下文、状态枚举转可读名、limit 收敛、缺身份 401。
func TestListSessions(t *testing.T) {
	gin.SetMode(gin.TestMode)
	hub := NewHub()

	var seenLimit int32
	mock := &mockSessionClient{
		listFn: func(limit int32) ([]*sessionv1.SessionListItem, error) {
			seenLimit = limit
			return []*sessionv1.SessionListItem{{
				SessionId:      "s-1",
				UserId:         "u1",
				Channel:        "web",
				State:          sessionv1.SessionState_WAITING_HUMAN,
				CreatedAtMs:    1000,
				LastActiveAtMs: 2000,
				MessageCount:   3,
				LastPreview:    "退款多久能到账？",
			}}, nil
		},
	}
	h := NewHandler(hub, mock)

	newRouter := func(withIdentity bool) *gin.Engine {
		router := gin.New()
		if withIdentity {
			router.Use(withTestTenant("tenant-ctx"))
		}
		router.GET("/api/v1/sessions", h.ListSessions)
		return router
	}

	t.Run("返回会话列表且状态为可读名", func(t *testing.T) {
		w := httptest.NewRecorder()
		newRouter(true).ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/api/v1/sessions", nil))
		if w.Code != http.StatusOK {
			t.Fatalf("状态码不符: %d", w.Code)
		}
		body := w.Body.String()
		// 枚举必须以名字下发（前端按 AI_ACTIVE / WAITING_HUMAN 渲染标签）
		for _, want := range []string{"s-1", "WAITING_HUMAN", "退款多久能到账？"} {
			if !strings.Contains(body, want) {
				t.Fatalf("响应缺少 %q: %s", want, body)
			}
		}
	})

	t.Run("limit 上界收敛为50", func(t *testing.T) {
		w := httptest.NewRecorder()
		newRouter(true).ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/api/v1/sessions?limit=200", nil))
		if seenLimit != 50 {
			t.Fatalf("limit 应收敛为 50，实际 %d", seenLimit)
		}
	})

	t.Run("limit 非法回落缺省20", func(t *testing.T) {
		w := httptest.NewRecorder()
		newRouter(true).ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/api/v1/sessions?limit=abc", nil))
		if seenLimit != 20 {
			t.Fatalf("非法 limit 应回落 20，实际 %d", seenLimit)
		}
	})

	t.Run("缺租户上下文返回401", func(t *testing.T) {
		w := httptest.NewRecorder()
		newRouter(false).ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/api/v1/sessions", nil))
		if w.Code != http.StatusUnauthorized {
			t.Fatalf("缺身份应 401，实际 %d", w.Code)
		}
	})
}

func TestCreateSession(t *testing.T) {
	gin.SetMode(gin.TestMode)
	hub := NewHub()
	// 记录建会话时实际注入的租户，验证「租户一律取可信上下文」
	var seenTenant string
	mock := &mockSessionClient{
		createFn: func(userID, channel, tenantID string) (string, error) {
			seenTenant = tenantID
			return "session-" + userID, nil
		},
	}
	h := NewHandler(hub, mock)

	router := gin.New()
	router.Use(withTestTenant("tenant-ctx"))
	router.POST("/api/v1/session", h.CreateSession)

	t.Run("以可信上下文租户建会话", func(t *testing.T) {
		body := strings.NewReader(`{"user_id":"u1"}`)
		req := httptest.NewRequest(http.MethodPost, "/api/v1/session", body)
		req.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		router.ServeHTTP(w, req)

		if w.Code != http.StatusOK {
			t.Fatalf("应返回 200，实际 %d: %s", w.Code, w.Body.String())
		}
		var resp map[string]any
		if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
			t.Fatalf("响应解析失败: %v", err)
		}
		if resp["session_id"] != "session-u1" {
			t.Fatalf("session_id 不符: %+v", resp)
		}
		if seenTenant != "tenant-ctx" {
			t.Fatalf("租户应取可信上下文，实际 %q", seenTenant)
		}
	})

	t.Run("请求体租户与身份一致时透传上下文租户", func(t *testing.T) {
		body := strings.NewReader(`{"user_id":"u2","tenant_id":"tenant-ctx"}`)
		req := httptest.NewRequest(http.MethodPost, "/api/v1/session", body)
		req.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		router.ServeHTTP(w, req)

		if w.Code != http.StatusOK {
			t.Fatalf("应返回 200，实际 %d: %s", w.Code, w.Body.String())
		}
		if seenTenant != "tenant-ctx" {
			t.Fatalf("一致请求体租户仍以上下文为准，实际 %q", seenTenant)
		}
	})

	t.Run("伪造租户被拒", func(t *testing.T) {
		// M5-5.5：请求体租户与可信上下文不一致 → 403（不再静默采信请求体）
		body := strings.NewReader(`{"user_id":"u3","tenant_id":"tenant-other"}`)
		req := httptest.NewRequest(http.MethodPost, "/api/v1/session", body)
		req.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		router.ServeHTTP(w, req)

		if w.Code != http.StatusForbidden {
			t.Fatalf("伪造租户应返回 403，实际 %d", w.Code)
		}
	})

	t.Run("缺 user_id 拒绝", func(t *testing.T) {
		body := strings.NewReader(`{}`)
		req := httptest.NewRequest(http.MethodPost, "/api/v1/session", body)
		req.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		router.ServeHTTP(w, req)

		if w.Code != http.StatusBadRequest {
			t.Fatalf("缺 user_id 应返回 400，实际 %d", w.Code)
		}
	})
}

func TestPushAgentReply(t *testing.T) {
	gin.SetMode(gin.TestMode)
	hub := NewHub()
	mock := &mockSessionClient{
		sendFn: func(sessionID, sender, senderID, content string) (*sessionv1.SendMessageResponse, error) {
			return &sessionv1.SendMessageResponse{Seq: 7, MessageId: "m7"}, nil
		},
	}
	h := NewHandler(hub, mock)

	router := gin.New()
	router.Use(withTestTenant("tenant-ctx"))
	router.POST("/api/v1/agent/reply", h.PushAgentReply)

	t.Run("正常回复", func(t *testing.T) {
		body := strings.NewReader(`{"session_id":"s1","content":"已为您查询","agent_id":"a1"}`)
		req := httptest.NewRequest(http.MethodPost, "/api/v1/agent/reply", body)
		req.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		router.ServeHTTP(w, req)

		if w.Code != http.StatusOK {
			t.Fatalf("应返回 200，实际 %d: %s", w.Code, w.Body.String())
		}
		var resp map[string]any
		if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
			t.Fatalf("响应解析失败: %v", err)
		}
		if resp["seq"].(float64) != 7 {
			t.Fatalf("响应 seq 不符: %+v", resp)
		}
	})

	t.Run("缺少参数拒绝", func(t *testing.T) {
		body := strings.NewReader(`{"session_id":"s1"}`)
		req := httptest.NewRequest(http.MethodPost, "/api/v1/agent/reply", body)
		req.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		router.ServeHTTP(w, req)

		if w.Code != http.StatusBadRequest {
			t.Fatalf("缺参数应返回 400，实际 %d", w.Code)
		}
	})

	t.Run("非法请求体拒绝", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/agent/reply", strings.NewReader("not-json"))
		req.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		router.ServeHTTP(w, req)

		if w.Code != http.StatusBadRequest {
			t.Fatalf("非法请求体应返回 400，实际 %d", w.Code)
		}
	})
}
