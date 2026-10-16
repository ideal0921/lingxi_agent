package middleware

import (
	"log"
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/lingxi/gateway-go/internal/tenant"
)

// RequireRole 角色拦截中间件（M5-5.6）：在进入业务域前做粗粒度权限校验。
//
// 语义：命中任一角色即放行；`admin` 为超级角色恒放行；
// 鉴权关闭（本地免登录联调）时身份无角色，此时**跳过校验**以保证本地可用
// （生产必须开启鉴权，见 GATEWAY_ENABLE_AUTH）。
func RequireRole(roles ...string) gin.HandlerFunc {
	allowed := make(map[string]struct{}, len(roles))
	for _, role := range roles {
		allowed[role] = struct{}{}
	}
	return func(c *gin.Context) {
		identity, ok := tenant.FromGin(c)
		if !ok || !identity.HasTenant() {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "缺少租户上下文"})
			return
		}
		if identity.Role == "" {
			c.Next()
			return
		}
		if identity.Role == "admin" {
			c.Next()
			return
		}
		if _, hit := allowed[identity.Role]; hit {
			c.Next()
			return
		}
		log.Printf("[auth-denied] 角色不足已拒绝 tenant=%s user=%s role=%s path=%s",
			identity.TenantID, identity.UserID, identity.Role, c.Request.URL.Path)
		c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "当前账号无权限访问该功能"})
	}
}
