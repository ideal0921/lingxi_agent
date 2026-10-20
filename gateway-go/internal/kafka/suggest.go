// 快捷回复建议异步结果消费（M4-4.6 同步通道过载降级）。
//
// 链路：同步通道过载 → Java 返回 degraded+queued 转异步 → Python 生成后投递
// Kafka（lingxi.ai.suggest.result）→ 本消费者仅推送至该会话的坐席端连接。
// 以 session_id 为消息键；**绝不广播给用户端**，避免 AI 草稿泄漏对客。
package kafka

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"time"

	segmentio "github.com/segmentio/kafka-go"

	"github.com/lingxi/gateway-go/internal/trace"
	"github.com/lingxi/gateway-go/internal/ws"
)

// SuggestResultEvent 对应 Python 侧 lingxi.ai.suggest.result 消息体。
type SuggestResultEvent struct {
	SessionID   string   `json:"session_id"`
	RequestID   string   `json:"request_id"`
	Suggestions []string `json:"suggestions"`
	// TraceID 链路 ID（M5-5.9）：由上游消息体注入；缺省即不记 span（缺字段不 panic）。
	TraceID string `json:"trace_id,omitempty"`
}

// SuggestBroadcaster 仅向指定会话中指定角色的连接广播下行信封（由 ws.Hub 实现）。
type SuggestBroadcaster interface {
	BroadcastToRole(sessionID, role string, env *ws.Envelope)
}

// DecodeSuggestResult 反序列化异步建议结果；非法 JSON 返回错误（调用方跳过该消息）。
func DecodeSuggestResult(data []byte) (*SuggestResultEvent, error) {
	var event SuggestResultEvent
	if err := json.Unmarshal(data, &event); err != nil {
		return nil, fmt.Errorf("解析建议结果失败: %w", err)
	}
	return &event, nil
}

// ToSuggestEnvelope 建议结果 → 网关下行信封（纯函数，便于单测）。
func ToSuggestEnvelope(event *SuggestResultEvent) *ws.Envelope {
	return &ws.Envelope{
		Type:        ws.MsgTypeSuggest,
		SessionID:   event.SessionID,
		Sender:      "agent",
		RequestID:   event.RequestID,
		Suggestions: event.Suggestions,
		// 携带链路 ID，使坐席端下行 WSS span 与后端生成段挂到同一条链。
		TraceID: event.TraceID,
	}
}

// SuggestConsumer 消费异步建议结果并仅推送至坐席端连接。
type SuggestConsumer struct {
	reader   *segmentio.Reader
	hub      SuggestBroadcaster
	recorder trace.Recorder
}

// NewSuggestConsumer 创建消费者；brokers 为集群地址，topic/group 为订阅主题与消费组；
// recorder 为链路追踪记录器（可为 nil）。
func NewSuggestConsumer(brokers []string, topic, groupID string, hub SuggestBroadcaster, recorder trace.Recorder) *SuggestConsumer {
	return &SuggestConsumer{
		hub:      hub,
		recorder: recorder,
		reader: segmentio.NewReader(segmentio.ReaderConfig{
			Brokers:  brokers,
			Topic:    topic,
			GroupID:  groupID,
			MinBytes: 1,
			MaxBytes: 1 << 20, // 建议结果很小，1MB 上限足够
		}),
	}
}

// Run 阻塞消费直到 ctx 取消；单条消息处理失败不中断循环。
func (c *SuggestConsumer) Run(ctx context.Context) {
	defer func() {
		if err := c.reader.Close(); err != nil {
			log.Printf("kafka: 关闭建议消费者失败: %v", err)
		}
	}()
	log.Printf("kafka: 建议结果消费者已启动 topic=%s", c.reader.Config().Topic)

	for {
		msg, err := c.reader.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return // 停机信号：正常退出
			}
			log.Printf("kafka: 读取建议结果失败: %v", err)
			continue
		}
		started := time.Now()
		if event, derr := DecodeSuggestResult(msg.Value); derr != nil {
			log.Printf("kafka: 跳过非法建议结果消息: %v", derr)
		} else {
			// 消息体含 trace_id 才记 span（缺字段不 panic）。
			recordKafkaSpan(c.recorder, c.reader.Config().Topic, event.TraceID, "", started, "")
			// 仅推送该会话的坐席端连接，绝不泄漏给用户端。
			if event.SessionID != "" {
				c.hub.BroadcastToRole(event.SessionID, "agent", ToSuggestEnvelope(event))
			}
		}
		if cerr := c.reader.CommitMessages(ctx, msg); cerr != nil && ctx.Err() == nil {
			log.Printf("kafka: 提交位点失败: %v", cerr)
		}
	}
}
