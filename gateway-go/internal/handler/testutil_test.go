package handler

import (
	"github.com/gin-gonic/gin"

	"github.com/lingxi/gateway-go/internal/tenant"
)

// withTestTenant 注入测试用可信租户身份，模拟鉴权中间件（M5-5.5）。
// 租户不再由请求参数决定，故处理器单测必须先注入身份。
func withTestTenant(tenantID string) gin.HandlerFunc {
	return func(c *gin.Context) {
		tenant.SetToGin(c, tenant.Identity{TenantID: tenantID, UserID: "u-test", Role: "agent"})
		c.Next()
	}
}
