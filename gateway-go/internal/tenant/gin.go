package tenant

import (
	"context"

	"github.com/gin-gonic/gin"

	"github.com/lingxi/gateway-go/internal/trace"
)

// ginKeyIdentity 请求上下文中租户身份的存储键（仅本包使用）。
const ginKeyIdentity = "lingxi.tenant.identity"

// SetToGin 把可信租户身份写入请求上下文（仅由鉴权/租户中间件调用）。
func SetToGin(c *gin.Context, identity Identity) {
	c.Set(ginKeyIdentity, identity)
}

// FromGin 读取当前请求的租户身份。
func FromGin(c *gin.Context) (Identity, bool) {
	value, exists := c.Get(ginKeyIdentity)
	if !exists {
		return Identity{}, false
	}
	identity, ok := value.(Identity)
	return identity, ok
}

// ClientContext 构造出站调用上下文：请求上下文 + 租户 metadata + 链路 ID（M5-5.9）。
// 未注入身份时退化为原始请求上下文（下游拦截器会按缺少租户拒绝，属预期行为）；
// 链路 ID 与租户正交，缺租户时仍会下传，便于下游为被拒调用记 span。
func ClientContext(c *gin.Context) context.Context {
	ctx := c.Request.Context()
	if identity, ok := FromGin(c); ok {
		ctx = WithOutgoing(ctx, identity)
	}
	return WithOutgoingTrace(ctx, trace.FromGin(c))
}
