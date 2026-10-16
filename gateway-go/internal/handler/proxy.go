package handler

import (
	"io"
	"log"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"

	"github.com/lingxi/gateway-go/internal/client"
)

// maxProxyBodyBytes 透传请求体上限（1MB）：管理接口均为小请求体，超出即拒绝，防内存放大。
const maxProxyBodyBytes = 1 << 20

// Proxy 管理接口透传处理器（M5-5.6）：把前端请求按 Java 侧路径模板转发，
// 响应（含状态码）原样回传，前端据此区分业务拒绝与通道故障。
//
// javaPath 支持 `:param` 占位（与 gin 路由参数同名），例如
// `/api/v1/users/:user_id/password` → `/api/v1/users/<真实值>/password`。
type Proxy struct {
	forwarder *client.RestForwarder
}

// NewProxy 创建透传处理器。
func NewProxy(forwarder *client.RestForwarder) *Proxy {
	return &Proxy{forwarder: forwarder}
}

// Forward 生成指定 Java 路径的 gin 处理器。
func (p *Proxy) Forward(javaPath string) gin.HandlerFunc {
	return func(c *gin.Context) {
		body, err := io.ReadAll(io.LimitReader(c.Request.Body, maxProxyBodyBytes))
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "请求体读取失败"})
			return
		}
		query := stripTokenParam(c.Request.URL.RawQuery)
		resp, err := p.forwarder.Forward(c.Request.Context(), client.ForwardRequest{
			Method:        c.Request.Method,
			Path:          resolveJavaPath(javaPath, c),
			RawQuery:      query,
			Body:          body,
			Authorization: c.GetHeader("Authorization"),
			ClientIP:      proxyClientIP(c),
			UserAgent:     c.GetHeader("User-Agent"),
		})
		if err != nil {
			log.Printf("proxy: 转发失败 path=%s err=%v", javaPath, err)
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "管理服务暂不可用"})
			return
		}
		if resp.ContentType != "" {
			c.Header("Content-Type", resp.ContentType)
		}
		c.Status(resp.Status)
		_, _ = c.Writer.Write(resp.Body)
	}
}

// resolveJavaPath 将路径模板中的 `:param` 替换为实际路由参数值。
func resolveJavaPath(javaPath string, c *gin.Context) string {
	out := javaPath
	for _, segment := range strings.Split(javaPath, "/") {
		if !strings.HasPrefix(segment, ":") {
			continue
		}
		name := strings.TrimPrefix(segment, ":")
		out = strings.Replace(out, segment, c.Param(name), 1)
	}
	return out
}

// stripTokenParam 移除查询串中的 token：令牌仅用于网关鉴权，不应随转发请求进入下游日志。
func stripTokenParam(rawQuery string) string {
	values := client.ParseQuery(rawQuery)
	if values.Get("token") == "" {
		return rawQuery
	}
	values.Del("token")
	return values.Encode()
}

// proxyClientIP 取真实来源 IP（供 Java 侧操作日志记录）：优先 X-Forwarded-For，其次直连地址。
func proxyClientIP(c *gin.Context) string {
	if xff := c.GetHeader("X-Forwarded-For"); xff != "" {
		if idx := strings.Index(xff, ","); idx > 0 {
			return strings.TrimSpace(xff[:idx])
		}
		return strings.TrimSpace(xff)
	}
	if idx := strings.LastIndex(c.Request.RemoteAddr, ":"); idx > 0 {
		return c.Request.RemoteAddr[:idx]
	}
	return c.Request.RemoteAddr
}
