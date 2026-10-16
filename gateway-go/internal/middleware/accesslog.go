// Package middleware 网关通用中间件：访问日志、令牌桶限流、JWT 鉴权与租户上下文。
package middleware

import (
	"log"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/lingxi/gateway-go/internal/tenant"
)

// AccessLog 记录每个请求的方法、路径、状态码、耗时与**租户身份**，用于访问审计。
//
// M5-5.5：补 tenant/user/role 维度，便于按租户检索访问记录与定位越权尝试。
func AccessLog() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		c.Next()

		identity, _ := tenant.FromGin(c)
		log.Printf("[access] tenant=%s user=%s role=%s ip=%s %s %s status=%d cost=%s",
			auditValue(identity.TenantID), auditValue(identity.UserID), auditValue(identity.Role),
			clientIP(c), c.Request.Method, c.Request.URL.Path, c.Writer.Status(), time.Since(start))
	}
}

// auditValue 空值统一为 "-"，保证日志字段可对齐解析。
func auditValue(value string) string {
	if value == "" {
		return "-"
	}
	return value
}
