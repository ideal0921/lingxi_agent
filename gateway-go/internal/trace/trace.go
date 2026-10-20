// Package trace 提供 API 接口链路追踪的跨端契约与网关侧落库能力（M5-5.9）。
//
// 契约（三端统一，见计划文档 §三）：
//   - HTTP header 与 gRPC metadata 统一使用 `x-trace-id`；网关是链路起点，
//     入站已携带则复用，否则生成；并回写响应头 `X-Trace-Id` 便于前端排障取用。
//   - Kafka 通过**消息体 JSON 字段** `trace_id` 传递（不用 Kafka header，与 request_id 范式一致）。
//   - 落库一律旁路异步（见 Writer），任何写库异常都不得影响请求链路。
package trace

import (
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

// 跨端统一的链路标识载体与常量。
const (
	// HeaderTraceID HTTP 请求头键，同时也是 gRPC metadata 键（三者同值）。
	HeaderTraceID = "x-trace-id"
	// ResponseHeaderTraceID 网关回写响应头，前端与排障可直接取用。
	ResponseHeaderTraceID = "X-Trace-Id"
	// MetadataTraceID 出站 gRPC metadata 键；与 HeaderTraceID 同值，语义上区分使用场景。
	MetadataTraceID = HeaderTraceID
)

// ginKeyTraceID 链路 ID 在 gin 上下文中的存储键（仅本包使用）。
const ginKeyTraceID = "lingxi.trace.id"

// ID 生成新的链路/Span 标识：32 位十六进制（UUID 去连字符），满足 VARCHAR(64) 列宽。
func ID() string {
	return strings.ReplaceAll(uuid.NewString(), "-", "")
}

// Set 把链路 ID 写入请求上下文。
func Set(c *gin.Context, id string) {
	if c == nil {
		return
	}
	c.Set(ginKeyTraceID, id)
}

// FromGin 读取当前请求的链路 ID；未设置时返回空串。
func FromGin(c *gin.Context) string {
	if c == nil {
		return ""
	}
	value, exists := c.Get(ginKeyTraceID)
	if !exists {
		return ""
	}
	id, _ := value.(string)
	return strings.TrimSpace(id)
}
