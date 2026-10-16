// Package httpx 提供网关通用的 HTTP 适配工具。
package httpx

import (
	"net/http"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// StatusFromGRPC 将下游 gRPC 状态码映射为 HTTP 状态码（M5-5.5）。
//
// 为什么需要：租户隔离后下游会以「不存在（NOT_FOUND）/未认证（UNAUTHENTICATED）/
// 越权（PERMISSION_DENIED）/超配额（RESOURCE_EXHAUSTED）」等表达拒绝，
// 若一律回落 503，调用方无法区分「资源不存在」与「通道故障」，也会掩盖越权信号。
// 未识别的状态码仍按通道故障处理（503）。
func StatusFromGRPC(err error) int {
	if err == nil {
		return http.StatusOK
	}
	switch status.Code(err) {
	case codes.InvalidArgument:
		return http.StatusBadRequest
	case codes.Unauthenticated:
		return http.StatusUnauthorized
	case codes.PermissionDenied:
		return http.StatusForbidden
	case codes.NotFound:
		return http.StatusNotFound
	case codes.ResourceExhausted:
		return http.StatusTooManyRequests
	case codes.FailedPrecondition, codes.Aborted:
		return http.StatusConflict
	default:
		return http.StatusServiceUnavailable
	}
}
