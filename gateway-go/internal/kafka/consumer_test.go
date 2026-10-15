package kafka

import (
	"encoding/json"
	"testing"

	"github.com/lingxi/gateway-go/internal/ws"
)

func TestDecodeChunkParsesContract(t *testing.T) {
	// 契约字段与 Python 侧 build_chunk_payload 保持一致
	raw := []byte(`{"session_id":"s1","message_id":"ai-1","index":2,"delta":"你好","finished":false}`)

	event, err := DecodeChunk(raw)
	if err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if event.SessionID != "s1" || event.MessageID != "ai-1" || event.Index != 2 || event.Delta != "你好" {
		t.Fatalf("字段映射不符: %+v", event)
	}
	if event.Finished {
		t.Fatalf("未结束时 Finished 应为 false")
	}
}

func TestDecodeChunkRejectsInvalidJSON(t *testing.T) {
	if _, err := DecodeChunk([]byte("not-json")); err == nil {
		t.Fatal("非法 JSON 应返回错误")
	}
}

func TestToEnvelopeMapsChunkFields(t *testing.T) {
	event := &ChunkEvent{
		SessionID: "s1", MessageID: "ai-1", Index: 3, Delta: "片段", Finished: true, Error: "boom",
	}

	env := ToEnvelope(event)

	if env.Type != ws.MsgTypeChunk {
		t.Fatalf("类型应为 chunk，实际 %s", env.Type)
	}
	if env.Sender != "agent" {
		t.Fatalf("发送者应为 agent，实际 %s", env.Sender)
	}
	if env.Content != "片段" || env.Index != 3 || !env.Finished || env.Error != "boom" {
		t.Fatalf("信封字段映射不符: %+v", env)
	}
	if env.Seq != 0 {
		t.Fatalf("流式片段不应推进会话游标 seq")
	}
}

func TestToEnvelopeMapsInterruptedFields(t *testing.T) {
	// M4-4.5 人工接管：终帧携带 interrupted 与 reason，需正确映射到信封
	event := &ChunkEvent{
		SessionID: "s1", MessageID: "ai-1", Index: 5, Delta: "", Finished: true,
		Interrupted: true, Reason: "agent_takeover",
	}

	env := ToEnvelope(event)

	if !env.Interrupted {
		t.Fatalf("Interrupted 应为 true")
	}
	if env.Reason != "agent_takeover" {
		t.Fatalf("Reason 映射不符，实际 %q", env.Reason)
	}

	// interrupted 不使用 omitempty，序列化后必须显式存在；reason 也应存在
	raw, err := json.Marshal(env)
	if err != nil {
		t.Fatalf("序列化失败: %v", err)
	}
	var decoded map[string]any
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatalf("反序列化失败: %v", err)
	}
	if v, ok := decoded["interrupted"]; !ok || v.(bool) != true {
		t.Fatalf("interrupted 键应显式为 true: %s", raw)
	}
	if decoded["reason"] != "agent_takeover" {
		t.Fatalf("reason 键应为 agent_takeover: %s", raw)
	}
}

func TestEnvelopeJSONShapeForFrontend(t *testing.T) {
	// 前端按 type/message_id/index/content/finished/error 解析，需保证序列化键名稳定
	env := ToEnvelope(&ChunkEvent{SessionID: "s1", MessageID: "ai-1", Index: 0, Delta: "x"})

	raw, err := json.Marshal(env)
	if err != nil {
		t.Fatalf("序列化失败: %v", err)
	}
	var decoded map[string]any
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatalf("反序列化失败: %v", err)
	}
	for _, key := range []string{"type", "session_id", "message_id", "index", "content", "finished"} {
		if _, ok := decoded[key]; !ok {
			t.Fatalf("缺少键 %s: %s", key, raw)
		}
	}
	// index=0 与 finished=false 必须显式下发（前端据此判定首帧与流状态）
	if decoded["index"].(float64) != 0 {
		t.Fatalf("index 应为数值 0: %s", raw)
	}
	if decoded["finished"].(bool) {
		t.Fatalf("finished 应为 false: %s", raw)
	}
}
