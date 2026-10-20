package trace

import "time"

// 服务与组件枚举：与 lingxi_api_trace 表列取值域一致（见 schema.sql）。
const (
	// ServiceGateway 网关服务名（三端 span 用它区分来源）。
	ServiceGateway = "gateway-go"

	// ComponentHTTP HTTP 请求段；ComponentGRPC gRPC 调用段；
	// ComponentKafka Kafka 消费段；ComponentWSS WebSocket 消息段。
	ComponentHTTP  = "HTTP"
	ComponentGRPC  = "GRPC"
	ComponentKafka = "KAFKA"
	ComponentWSS   = "WSS"

	// StatusOK / StatusError span 结果枚举。
	StatusOK    = "OK"
	StatusError = "ERROR"
)

// Span 一条链路调用记录，字段与 lingxi_api_trace 表列一一对应。
//
// 设计上不落请求体/响应体（PII 合规红线），error_msg 仅存截断后的错误摘要。
type Span struct {
	SpanID       string // Span 标识（本段唯一）
	TraceID      string // 链路标识（网关生成，三端贯穿）
	ParentSpanID string // 上游 Span（用于还原调用关系；网关为根，留空）
	Service      string // 来源服务：gateway-go
	Component    string // HTTP / GRPC / KAFKA / WSS
	Entry        string // 入口：HTTP 路径 / gRPC 方法 / Kafka topic / WSS 消息类型
	HTTPMethod   string // HTTP 方法（非 HTTP 段为空）
	Status       string // OK / ERROR
	StatusCode   int    // HTTP 状态码 / gRPC 状态码（无则 0）
	TenantID     string
	UserID       string
	Role         string
	ClientIP     string
	ElapsedMs    int64
	ErrorMsg     string
	StartedAt    int64 // epoch ms
}

// Recorder span 记录器的最小抽象：便于中间件/消费者以接口注入，测试可替换为桩实现。
type Recorder interface {
	Record(span Span)
}

// NewSpan 构造网关 span 骨架（统一填充 span_id/service/status/started_at），
// 调用方按需补充 component/entry/耗时等字段。
func NewSpan(traceID, component, entry string) Span {
	return Span{
		SpanID:    ID(),
		TraceID:   traceID,
		Service:   ServiceGateway,
		Component: component,
		Entry:     entry,
		Status:    StatusOK,
		StartedAt: time.Now().UnixMilli(),
	}
}
