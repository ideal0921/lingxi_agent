package tenant

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	"google.golang.org/grpc/metadata"
)

// newTestRequest 构造测试用请求（ClientContext 依赖 Request.Context）。
func newTestRequest() *http.Request {
	return httptest.NewRequest(http.MethodGet, "/", nil)
}

func TestWithOutgoingInjectsIdentity(t *testing.T) {
	ctx := WithOutgoing(context.Background(), Identity{TenantID: "t1", UserID: "u1", Role: "agent"})
	md, ok := metadata.FromOutgoingContext(ctx)
	if !ok {
		t.Fatal("应写入出站 metadata")
	}
	if got := md.Get(MetadataTenantID); len(got) != 1 || got[0] != "t1" {
		t.Fatalf("租户 metadata 不符: %v", got)
	}
	if got := md.Get(MetadataUserID); len(got) != 1 || got[0] != "u1" {
		t.Fatalf("用户 metadata 不符: %v", got)
	}
	if got := md.Get(MetadataRole); len(got) != 1 || got[0] != "agent" {
		t.Fatalf("角色 metadata 不符: %v", got)
	}
}

func TestWithOutgoingSkipsEmptyFields(t *testing.T) {
	// 缺少租户时不注入 x-tenant-id：由下游拦截器按「缺少租户上下文」显式拒绝，
	// 避免用空值掩盖问题
	ctx := WithOutgoing(context.Background(), Identity{UserID: "u1"})
	md, _ := metadata.FromOutgoingContext(ctx)
	if got := md.Get(MetadataTenantID); len(got) != 0 {
		t.Fatalf("空租户不应注入 metadata，实际 %v", got)
	}
}

func TestWithOutgoingNoIdentityKeepsContext(t *testing.T) {
	base := context.Background()
	ctx := WithOutgoing(base, Identity{})
	if _, ok := metadata.FromOutgoingContext(ctx); ok {
		t.Fatal("空身份不应产生 metadata")
	}
}

func TestGinRoundTripAndClientContext(t *testing.T) {
	gin.SetMode(gin.TestMode)
	c, _ := gin.CreateTestContext(nil)
	c.Request = newTestRequest()

	if _, ok := FromGin(c); ok {
		t.Fatal("未注入时不应读到身份")
	}
	SetToGin(c, Identity{TenantID: "t9", UserID: "u9", Role: "admin"})
	identity, ok := FromGin(c)
	if !ok || identity.TenantID != "t9" || !identity.HasTenant() {
		t.Fatalf("身份读回不符: %+v ok=%v", identity, ok)
	}

	md, ok := metadata.FromOutgoingContext(ClientContext(c))
	if !ok || len(md.Get(MetadataTenantID)) != 1 || md.Get(MetadataTenantID)[0] != "t9" {
		t.Fatalf("ClientContext 未携带租户 metadata: %v", md)
	}
}

func TestClientContextWithoutIdentity(t *testing.T) {
	gin.SetMode(gin.TestMode)
	c, _ := gin.CreateTestContext(nil)
	c.Request = newTestRequest()
	if _, ok := metadata.FromOutgoingContext(ClientContext(c)); ok {
		t.Fatal("无身份时不应注入 metadata（由下游拒绝）")
	}
}
