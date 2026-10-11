package middleware

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
)

// Auth JWT 鉴权中间件占位。
// M1 骨架阶段：仅校验 Authorization 头格式并将租户上下文注入请求，
// 完整签名校验在 M2 接入会话域后实现（密钥来自环境变量，不硬编码）。
func Auth() gin.HandlerFunc {
	return func(c *gin.Context) {
		token := c.GetHeader("Authorization")
		if !strings.HasPrefix(token, "Bearer ") {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{
				"code":    "UNAUTHORIZED",
				"message": "缺少有效的身份凭证",
			})
			return
		}
		// TODO(M2): 校验 JWT 签名并注入 tenantId / userId
		c.Set("token", strings.TrimPrefix(token, "Bearer "))
		c.Next()
	}
}
