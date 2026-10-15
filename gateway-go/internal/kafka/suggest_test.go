package kafka

import (
	"encoding/json"
	"testing"

	"github.com/lingxi/gateway-go/internal/ws"
)

func TestDecodeSuggestResultParsesContract(t *testing.T) {
	// 契约字段与 Python 侧异步建议结果保持一致
	raw := []byte(`{"session_id":"s1","request_id":"req-1","suggestions":["您好，","稍等，正在为您查询"]}`)

	event, err := DecodeSuggestResult(raw)
	if err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if event.SessionID != "s1" || event.RequestID != "req-1" {
		t.Fatalf("字段映射不符: %+v", event)
	}
	if len(event.Suggestions) != 2 || event.Suggestions[0] != "您好，" {
		t.Fatalf("候选解析不符: %+v", event.Suggestions)
	}
}

func TestDecodeSuggestResultRejectsInvalidJSON(t *testing.T) {
	if _, err := DecodeSuggestResult([]byte("not-json")); err == nil {
		t.Fatal("非法 JSON 应返回错误")
	}
}

func TestToSuggestEnvelopeMapsFields(t *testing.T) {
	event := &SuggestResultEvent{
		SessionID: "s1", RequestID: "req-1", Suggestions: []string{"a", "b"},
	}

	env := ToSuggestEnvelope(event)

	if env.Type != ws.MsgTypeSuggest {
		t.Fatalf("类型应为 suggest，实际 %s", env.Type)
	}
	if env.Sender != "agent" {
		t.Fatalf("发送者应为 agent，实际 %s", env.Sender)
	}
	if env.SessionID != "s1" || env.RequestID != "req-1" {
		t.Fatalf("会话/请求标识映射不符: %+v", env)
	}
	if len(env.Suggestions) != 2 || env.Suggestions[1] != "b" {
		t.Fatalf("候选映射不符: %+v", env.Suggestions)
	}
}

func TestSuggestEnvelopeJSONShapeForFrontend(t *testing.T) {
	// 前端按 type/session_id/request_id/suggestions 解析，需保证序列化键名稳定
	env := ToSuggestEnvelope(&SuggestResultEvent{SessionID: "s1", RequestID: "req-1", Suggestions: []string{"a"}})

	raw, err := json.Marshal(env)
	if err != nil {
		t.Fatalf("序列化失败: %v", err)
	}
	var decoded map[string]any
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatalf("反序列化失败: %v", err)
	}
	for _, key := range []string{"type", "session_id", "request_id", "suggestions"} {
		if _, ok := decoded[key]; !ok {
			t.Fatalf("缺少键 %s: %s", key, raw)
		}
	}
	if decoded["type"] != "suggest" || decoded["request_id"] != "req-1" {
		t.Fatalf("键值不符: %s", raw)
	}
}
