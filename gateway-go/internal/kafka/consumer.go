// Package kafka 消费 AI 流式片段并推送至 WSS 会话（M3-3.8 流式全链路）。
//
// 链路：Python 流式生成 → Kafka（lingxi.ai.chunk）→ 本消费者 → Hub 广播 chunk 信封
// → 前端打字机渲染。以 session_id 为消息键，保证同一会话的片段有序。
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

// ChunkEvent 对应 Python 侧 lingxi.ai.chunk 消息体（契约见 ai-python/app/consumers/session_message.py）。
type ChunkEvent struct {
	SessionID   string `json:"session_id"`
	MessageID   string `json:"message_id"`
	Index       int    `json:"index"`
	Delta       string `json:"delta"`
	Finished    bool   `json:"finished"`
	Interrupted bool   `json:"interrupted"`
	Reason      string `json:"reason,omitempty"`
	Error       string `json:"error,omitempty"`
	// TraceID 链路 ID（M5-5.9）：由上游消息体注入，用于把 AI 段挂到同一条链；缺省即不记 span。
	TraceID string `json:"trace_id,omitempty"`
	// TenantID 租户（M5-5.9）：由 AI 域在片段消息中回带，使管理端能按租户检索到流式片段 span
	// （否则该 span 因租户为空被租户强过滤排除）。
	TenantID string `json:"tenant_id,omitempty"`
}

// Broadcaster 向指定会话的全部连接广播下行信封（由 ws.Hub 实现）。
type Broadcaster interface {
	Broadcast(sessionID string, env *ws.Envelope)
}

// DecodeChunk 反序列化片段消息；非法 JSON 返回错误（调用方跳过该消息）。
func DecodeChunk(data []byte) (*ChunkEvent, error) {
	var event ChunkEvent
	if err := json.Unmarshal(data, &event); err != nil {
		return nil, fmt.Errorf("解析片段事件失败: %w", err)
	}
	return &event, nil
}

// ToEnvelope 片段事件 → 网关下行信封（纯函数，便于单测）。
func ToEnvelope(event *ChunkEvent) *ws.Envelope {
	return &ws.Envelope{
		Type:        ws.MsgTypeChunk,
		SessionID:   event.SessionID,
		Sender:      "agent",
		Content:     event.Delta,
		MessageID:   event.MessageID,
		Index:       event.Index,
		Finished:    event.Finished,
		Interrupted: event.Interrupted,
		Reason:      event.Reason,
		Error:       event.Error,
		// 携带链路 ID，使后续 WSS 下行 span 与 AI 生成段挂到同一条链。
		TraceID: event.TraceID,
	}
}

// Consumer 消费 AI 流式片段并广播。
type Consumer struct {
	reader   *segmentio.Reader
	hub      Broadcaster
	recorder trace.Recorder
}

// NewConsumer 创建消费者；brokers 为集群地址，topic/group 为订阅主题与消费组；
// recorder 为链路追踪记录器（可为 nil）。
func NewConsumer(brokers []string, topic, groupID string, hub Broadcaster, recorder trace.Recorder) *Consumer {
	return &Consumer{
		hub:      hub,
		recorder: recorder,
		reader: segmentio.NewReader(segmentio.ReaderConfig{
			Brokers:  brokers,
			Topic:    topic,
			GroupID:  groupID,
			MinBytes: 1,
			MaxBytes: 1 << 20, // 片段很小，1MB 上限足够
		}),
	}
}

// Run 阻塞消费直到 ctx 取消；单条消息处理失败不中断循环。
func (c *Consumer) Run(ctx context.Context) {
	defer func() {
		if err := c.reader.Close(); err != nil {
			log.Printf("kafka: 关闭消费者失败: %v", err)
		}
	}()
	log.Printf("kafka: 片段消费者已启动 topic=%s", c.reader.Config().Topic)

	for {
		msg, err := c.reader.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return // 停机信号：正常退出
			}
			log.Printf("kafka: 读取片段失败: %v", err)
			continue
		}
		started := time.Now()
		if event, derr := DecodeChunk(msg.Value); derr != nil {
			log.Printf("kafka: 跳过非法片段消息: %v", derr)
		} else {
			// 消息体含 trace_id 才记 span：把 AI 段挂到网关发起的同一条链（缺字段不 panic）。
			recordKafkaSpan(c.recorder, c.reader.Config().Topic, event.TraceID, event.TenantID, started, event.Error)
			if event.SessionID != "" {
				c.hub.Broadcast(event.SessionID, ToEnvelope(event))
			}
		}
		if cerr := c.reader.CommitMessages(ctx, msg); cerr != nil && ctx.Err() == nil {
			log.Printf("kafka: 提交位点失败: %v", cerr)
		}
	}
}

// recordKafkaSpan 记录一条 KAFKA span：仅当消息体携带 trace_id 时落库，
// 使 AI 生成段挂到网关发起的同一条链；recorder 为空或 trace_id 缺失时静默（缺字段不 panic）。
func recordKafkaSpan(recorder trace.Recorder, topic, traceID, tenantID string, started time.Time, errMsg string) {
	if recorder == nil || traceID == "" {
		return
	}
	span := trace.Span{
		SpanID:    trace.ID(),
		TraceID:   traceID,
		Service:   trace.ServiceGateway,
		Component: trace.ComponentKafka,
		Entry:     topic,
		TenantID:  tenantID,
		Status:    trace.StatusOK,
		ElapsedMs: time.Since(started).Milliseconds(),
		StartedAt: started.UnixMilli(),
	}
	if errMsg != "" {
		span.Status = trace.StatusError
		span.ErrorMsg = errMsg
	}
	recorder.Record(span)
}
