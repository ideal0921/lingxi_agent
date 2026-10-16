package middleware

import (
	"log"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"

	"github.com/lingxi/gateway-go/internal/auth"
	"github.com/lingxi/gateway-go/internal/tenant"
)

// 本地联调头（仅在鉴权关闭时生效，用于多租户联调；生产必须开启鉴权）。
const (
	headerTenantID = "X-Tenant-Id"
	headerUserID   = "X-User-Id"
	headerRole     = "X-Role"
)

// Auth JWT 鉴权与租户上下文中间件（M5-5.5）。
//
// 行为：
//   - enableAuth=true：强制 Bearer 令牌，校验 HS256 签名与时效，注入可信租户身份；
//     令牌非法/缺租户声明一律 401，绝不回落到默认租户（防伪造）。
//   - enableAuth=false（本地联调）：使用默认租户，允许以 X-Tenant-Id 头显式指定租户，
//     便于在单机验证多租户隔离；该路径在生产配置下不可达。
//
// 注入后的租户身份是下游唯一的租户来源：业务处理器不得再信任请求体中的 tenant_id。
func Auth(enableAuth bool, jwtSecret, defaultTenant string) gin.HandlerFunc {
	if !enableAuth {
		log.Printf("[warn] 网关鉴权已关闭：租户由默认值/联调头提供，仅限本地开发使用")
	}
	return func(c *gin.Context) {
		if !enableAuth {
			tenant.SetToGin(c, tenant.Identity{
				TenantID: firstNonEmpty(c.GetHeader(headerTenantID), defaultTenant),
				UserID:   c.GetHeader(headerUserID),
				Role:     c.GetHeader(headerRole),
			})
			c.Next()
			return
		}

		raw := resolveAuthorization(c)
		if raw == "" {
			abortUnauthorized(c, "缺少有效的身份凭证")
			return
		}
		claims, err := auth.ParseToken(strings.TrimPrefix(raw, "Bearer "), jwtSecret)
		if err != nil {
			log.Printf("[auth] 令牌校验失败 path=%s err=%v", c.Request.URL.Path, err)
			abortUnauthorized(c, "身份凭证无效或已过期")
			return
		}
		if strings.TrimSpace(claims.TenantID) == "" {
			abortUnauthorized(c, "身份凭证缺少租户声明")
			return
		}
		tenant.SetToGin(c, tenant.Identity{
			TenantID: claims.TenantID, UserID: claims.UserID, Role: claims.Role,
		})
		c.Next()
	}
}

// resolveAuthorization 解析凭证：优先 `Authorization: Bearer`，
// 其次查询参数 `token`（浏览器 WebSocket API 无法自定义请求头，WSS 握手只能用查询参数传令牌）。
// 返回值要么是规范化的 `Bearer <token>`，要么为空串。
func resolveAuthorization(c *gin.Context) string {
	header := c.GetHeader("Authorization")
	if strings.HasPrefix(header, "Bearer ") {
		return header
	}
	if token := c.Query("token"); token != "" {
		return "Bearer " + token
	}
	return ""
}

func abortUnauthorized(c *gin.Context, message string) {
	c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{
		"code":    "UNAUTHORIZED",
		"message": message,
	})
}

// firstNonEmpty 返回首个非空字符串。
func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return value
		}
	}
	return ""
}
