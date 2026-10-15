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

	segmentio "github.com/segmentio/kafka-go"

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
	}
}

// Consumer 消费 AI 流式片段并广播。
type Consumer struct {
	reader *segmentio.Reader
	hub    Broadcaster
}

// NewConsumer 创建消费者；brokers 为集群地址，topic/group 为订阅主题与消费组。
func NewConsumer(brokers []string, topic, groupID string, hub Broadcaster) *Consumer {
	return &Consumer{
		hub: hub,
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
		if event, derr := DecodeChunk(msg.Value); derr != nil {
			log.Printf("kafka: 跳过非法片段消息: %v", derr)
		} else if event.SessionID != "" {
			c.hub.Broadcast(event.SessionID, ToEnvelope(event))
		}
		if cerr := c.reader.CommitMessages(ctx, msg); cerr != nil && ctx.Err() == nil {
			log.Printf("kafka: 提交位点失败: %v", cerr)
		}
	}
}
