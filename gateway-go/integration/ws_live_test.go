//go:build integration

// Package integration M2-A5 实时链路集成验证。
// 前置条件：Java 会话域（localhost:9090）+ Go 网关（localhost:8080）均已启动。
// 运行：go test -tags integration ./integration/ -v
package integration

import (
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

const (
	gatewayWS   = "ws://localhost:8080/ws"
	gatewayAPI  = "http://localhost:8080/api/v1"
	integration = "integration"
)

// TestLiveChatLink 覆盖用户 ↔ 网关 ↔ Java 会话域完整闭环：
// 建会话 → WSS 发送消息 → ack → 坐席回复下行 → 断连后 resume 回源续传。
func TestLiveChatLink(t *testing.T) {
	// 1. 创建会话（网关 REST 转发 Java 会话域）。
	sessionID := createSession(t, "it-u1")

	// 2. WSS 连接并发送用户消息。
	conn := dialWS(t, sessionID)
	defer func() { _ = conn.Close() }()

	if err := conn.WriteJSON(map[string]any{
		"type": "send", "sender": "it-u1", "content": "你好，我订单还没有送达",
	}); err != nil {
		t.Fatalf("上行发送失败: %v", err)
	}

	// 3. 读取 ack，校验 seq 游标。
	ack := readEnvelope(t, conn, 5*time.Second)
	if ack["type"] != "ack" {
		t.Fatalf("应收到 ack，实际 %+v", ack)
	}
	userSeq := int64(ack["seq"].(float64))
	if userSeq != 1 {
		t.Fatalf("首条消息 seq 应为 1，实际 %d", userSeq)
	}

	// 4. 坐席回复下行（模拟客服工作台调用）。
	agentReply(t, sessionID, "您好，已为您核实，预计今晚送达")

	// 5. 用户连接应收到 chat 下行。
	chat := readEnvelope(t, conn, 5*time.Second)
	if chat["type"] != "chat" || chat["sender"] != "agent" {
		t.Fatalf("应收到坐席 chat 下行，实际 %+v", chat)
	}
	if int64(chat["seq"].(float64)) != userSeq+1 {
		t.Fatalf("坐席消息 seq 应连续，实际 %+v", chat)
	}

	// 6. 断连后 resume 续传：新连接携带 last_seq=0，应从 Java 回源补齐 2 条。
	conn.Close()
	resumed := dialWS(t, sessionID)
	defer func() { _ = resumed.Close() }()

	if err := resumed.WriteJSON(map[string]any{"type": "resume", "last_seq": 0}); err != nil {
		t.Fatalf("resume 上行失败: %v", err)
	}
	got := []map[string]any{}
	for len(got) < 2 {
		env := readEnvelope(t, resumed, 5*time.Second)
		if env["type"] == "chat" {
			got = append(got, env)
		}
	}
	if int64(got[0]["seq"].(float64)) != 1 || int64(got[1]["seq"].(float64)) != 2 {
		t.Fatalf("resume 回源消息顺序不符: %+v", got)
	}

	t.Logf("链路验证通过：userSeq=%d agentSeq=%d resume 补齐 %d 条", userSeq, userSeq+1, len(got))
}

func createSession(t *testing.T, userID string) string {
	t.Helper()
	resp, err := http.Post(gatewayAPI+"/session", "application/json",
		strings.NewReader(`{"user_id":"`+userID+`","channel":"web"}`))
	if err != nil {
		t.Fatalf("创建会话失败: %v", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("创建会话状态码 %d", resp.StatusCode)
	}
	var out struct {
		SessionID string `json:"session_id"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil || out.SessionID == "" {
		t.Fatalf("创建会话响应解析失败: %v body=%s", err, resp.Body)
	}
	return out.SessionID
}

func dialWS(t *testing.T, sessionID string) *websocket.Conn {
	t.Helper()
	conn, resp, err := websocket.DefaultDialer.Dial(gatewayWS+"?session_id="+sessionID, nil)
	if err != nil {
		if resp != nil {
			t.Fatalf("WSS 连接失败 status=%s: %v", resp.Status, err)
		}
		t.Fatalf("WSS 连接失败: %v", err)
	}
	return conn
}

func agentReply(t *testing.T, sessionID, content string) {
	t.Helper()
	body := `{"session_id":"` + sessionID + `","content":"` + content + `","agent_id":"a1"}`
	resp, err := http.Post(gatewayAPI+"/agent/reply", "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatalf("坐席回复调用失败: %v", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("坐席回复状态码 %d", resp.StatusCode)
	}
}

func readEnvelope(t *testing.T, conn *websocket.Conn, timeout time.Duration) map[string]any {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(timeout))
	_, data, err := conn.ReadMessage()
	if err != nil {
		t.Fatalf("读取 WSS 消息失败: %v", err)
	}
	var env map[string]any
	if err := json.Unmarshal(data, &env); err != nil {
		t.Fatalf("WSS 消息解析失败: %v raw=%s", err, data)
	}
	return env
}
