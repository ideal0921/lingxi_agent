package middleware

import (
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/lingxi/gateway-go/internal/tenant"
	"github.com/lingxi/gateway-go/internal/trace"
)

// 链路追踪的采集范围：只覆盖业务接口 `/api/v1/**`。
const (
	apiPathPrefix = "/api/v1/"
	healthPath    = "/api/v1/health"
)

// ApiLog 接口链路追踪中间件（M5-5.9）：只对 `/api/v1/**` 记 gateway span，跳过探活。
//
// 职责边界：与 AccessLog（stdout 访问日志）并存——
//   - 本中间件负责「绑定/回写链路 ID」并异步落库 span；
//   - AccessLog 仍负责人类可读的访问日志，二者互不替代。
//
// 为什么在全局链且位于 AccessLog 之后：链路 ID 需在鉴权/业务处理器之前绑定，
// 以便出站 REST 透传与 gRPC metadata 都能取到（网关是链路起点）。
// WSS（/ws）不在此范围：长连接本身不记 span，由 ws 包按「每条消息收发」埋点。
// defaultTenant 用于未认证请求（登录等豁免鉴权的路径）的 span 归属回落：
// 与 Java 侧 AuthService.login「租户缺省回落默认租户」的既有约定保持一致，
// 否则租户为空的 span 会因管理端的租户强过滤而查不到。
func ApiLog(recorder trace.Recorder, defaultTenant string) gin.HandlerFunc {
	return func(c *gin.Context) {
		if !shouldTracePath(c.Request.URL.Path) {
			c.Next()
			return
		}

		// 入站已带则复用（便于跨端联调/外部系统传入），否则网关生成。
		traceID := strings.TrimSpace(c.GetHeader(trace.HeaderTraceID))
		if traceID == "" {
			traceID = trace.ID()
		}
		// 回写请求头：后续处理器（如 REST 透传）统一从 header 取链路 ID，
		// 保证「透传失败也能拿到网关生成的值」。
		c.Request.Header.Set(trace.HeaderTraceID, traceID)
		trace.Set(c, traceID)
		// 回写响应头（在 c.Next 前设置，Abort 场景下也能带上）。
		c.Header(trace.ResponseHeaderTraceID, traceID)

		started := time.Now()
		c.Next()

		recordHTTPSpan(recorder, c, traceID, started, defaultTenant)
	}
}

// shouldTracePath 判断是否属于追踪范围：仅 `/api/v1/**`，排除探活接口。
func shouldTracePath(path string) bool {
	if !strings.HasPrefix(path, apiPathPrefix) {
		return false
	}
	return path != healthPath
}

// recordHTTPSpan 组装并投递 gateway 的 HTTP span（旁路，失败不影响请求）。
func recordHTTPSpan(recorder trace.Recorder, c *gin.Context, traceID string, started time.Time,
	defaultTenant string) {
	if recorder == nil {
		return
	}
	status := c.Writer.Status()
	span := trace.Span{
		SpanID:     trace.ID(),
		TraceID:    traceID,
		Service:    trace.ServiceGateway,
		Component:  trace.ComponentHTTP,
		Entry:      c.Request.URL.Path,
		HTTPMethod: c.Request.Method,
		Status:     trace.StatusOK,
		StatusCode: status,
		ClientIP:   clientIP(c),
		ElapsedMs:  time.Since(started).Milliseconds(),
		StartedAt:  started.UnixMilli(),
	}
	// 租户身份在 c.Next() 期间由鉴权中间件注入，此处读取到的是最终可信身份。
	if identity, ok := tenant.FromGin(c); ok {
		span.TenantID = identity.TenantID
		span.UserID = identity.UserID
		span.Role = identity.Role
	}
	// 未认证请求（如登录，鉴权被豁免）没有租户身份，回落默认租户，避免该 span 在管理端不可见。
	if span.TenantID == "" {
		span.TenantID = defaultTenant
	}
	if status >= http.StatusBadRequest {
		span.Status = trace.StatusError
		span.ErrorMsg = http.StatusText(status)
	}
	recorder.Record(span)
}
