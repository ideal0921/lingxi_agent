// Package middleware 网关通用中间件：访问日志、令牌桶限流、JWT 鉴权占位。
package middleware

import (
	"log"
	"time"

	"github.com/gin-gonic/gin"
)

// AccessLog 记录每个请求的方法、路径、状态码与耗时，用于访问审计。
func AccessLog() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		c.Next()
		log.Printf("[access] %s %s status=%d cost=%s",
			c.Request.Method, c.Request.URL.Path, c.Writer.Status(), time.Since(start))
	}
}
