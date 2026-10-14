// Package ws 实现会话长连接网关（M2-A3）。
// 职责：WSS 升级、心跳、断连缓冲与 seq 续传、用户上行消息转交 Java 会话域、坐席消息下行广播。
package ws

import "encoding/json"

// 消息类型：客户端与网关之间的协议信封。
const (
	MsgTypePing   = "ping"   // 客户端心跳
	MsgTypePong   = "pong"   // 网关心跳应答
	MsgTypeSend   = "send"   // 上行：用户发消息
	MsgTypeAck    = "ack"    // 下行：网关对 send 的确认（携带 seq）
	MsgTypeChat   = "chat"   // 下行：会话消息广播（坐席回复等）
	MsgTypeChunk  = "chunk"  // 下行：AI 流式片段（M3-3.8，前端据此打字机渲染）
	MsgTypeError  = "error"  // 下行：错误提示
	MsgTypeResume = "resume" // 上行：断连重连，携带 lastSeq
)

// Envelope 网关与客户端的统一消息信封。
type Envelope struct {
	Type      string `json:"type"` // 消息类型
	SessionID string `json:"session_id,omitempty"`
	Sender    string `json:"sender,omitempty"` // user / agent / system
	Content   string `json:"content,omitempty"`
	Seq       int64  `json:"seq,omitempty"`      // 会话内游标
	LastSeq   int64  `json:"last_seq,omitempty"` // resume 续传起点
	MessageID string `json:"message_id,omitempty"`
	// Index 流式片段序号（chunk 专用，**不使用 omitempty**：0 是合法序号，需显式下发）
	Index int `json:"index"`
	// Finished 是否本轮流结束（chunk 专用，**不使用 omitempty**：显式表达流状态）
	Finished  bool   `json:"finished"`
	Timestamp int64  `json:"timestamp_ms,omitempty"`
	Error     string `json:"error,omitempty"`
}

// parseEnvelope 反序列化客户端消息；非法 JSON 返回错误。
func parseEnvelope(data []byte) (*Envelope, error) {
	var env Envelope
	if err := json.Unmarshal(data, &env); err != nil {
		return nil, err
	}
	return &env, nil
}
