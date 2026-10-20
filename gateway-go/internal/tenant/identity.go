// Package tenant 提供「可信租户身份」的载体与跨进程传递约定（M5-5.5 租户隔离加固）。
//
// 设计要点：租户身份只能由接入层（JWT 解析）产生，业务处理器不得从请求体/查询参数
// 推导租户；出站 gRPC 调用统一经 metadata 下传，由 Java 侧拦截器强制校验。
package tenant

import (
	"context"
	"strings"

	"google.golang.org/grpc/metadata"

	"github.com/lingxi/gateway-go/internal/trace"
)

// Identity 可信租户身份（网关解析所得，随请求贯穿全链路）。
type Identity struct {
	// TenantID 租户标识（隔离主键）。
	TenantID string
	// UserID 用户标识（匿名接入为空）。
	UserID string
	// Role 主体角色：user / agent / admin（未知为空）。
	Role string
}

// HasTenant 是否具备可用租户（缺失即视为未认证）。
func (i Identity) HasTenant() bool {
	return strings.TrimSpace(i.TenantID) != ""
}

// gRPC metadata 键约定，与 Java 侧 TenantMetadata 保持一致。
const (
	MetadataTenantID = "x-tenant-id"
	MetadataUserID   = "x-user-id"
	MetadataRole     = "x-role"
	// MetadataTraceID 链路 ID（M5-5.9）：与 HTTP 头同值，由 trace 包统一定义。
	MetadataTraceID = trace.MetadataTraceID
)

// WithOutgoing 把租户身份写入出站 gRPC metadata。
// 仅注入非空字段：缺字段时由下游拦截器按「缺少租户上下文」拒绝，避免用空值掩盖问题。
func WithOutgoing(ctx context.Context, identity Identity) context.Context {
	pairs := make([]string, 0, 6)
	if id := strings.TrimSpace(identity.TenantID); id != "" {
		pairs = append(pairs, MetadataTenantID, id)
	}
	if uid := strings.TrimSpace(identity.UserID); uid != "" {
		pairs = append(pairs, MetadataUserID, uid)
	}
	if role := strings.TrimSpace(identity.Role); role != "" {
		pairs = append(pairs, MetadataRole, role)
	}
	if len(pairs) == 0 {
		return ctx
	}
	return metadata.AppendToOutgoingContext(ctx, pairs...)
}

// WithOutgoingTrace 把链路 ID 追加到出站 gRPC metadata（空值不注入）。
//
// 与租户身份正交：即使缺少租户（下游会拒绝），链路 ID 也应下传，
// 以便 Java 侧 TraceInterceptor 能为「被拒的调用」记 span（计划 §六.1）。
func WithOutgoingTrace(ctx context.Context, traceID string) context.Context {
	id := strings.TrimSpace(traceID)
	if id == "" {
		return ctx
	}
	return metadata.AppendToOutgoingContext(ctx, MetadataTraceID, id)
}
