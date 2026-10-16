package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"

	"github.com/lingxi/gateway-go/internal/tenant"
)

// newRoleRouter 构造「注入角色 + 角色拦截」的测试路由。
func newRoleRouter(role string, roles ...string) *gin.Engine {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(func(c *gin.Context) {
		tenant.SetToGin(c, tenant.Identity{TenantID: "t1", UserID: "u1", Role: role})
		c.Next()
	})
	router.GET("/secret", RequireRole(roles...), func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"ok": true})
	})
	return router
}

func doRole(router *gin.Engine) int {
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/secret", nil))
	return rec.Code
}

// TestRequireRoleAllowsListedRole 命中角色列表即放行。
func TestRequireRoleAllowsListedRole(t *testing.T) {
	if code := doRole(newRoleRouter("agent", "admin", "agent")); code != http.StatusOK {
		t.Fatalf("agent 应在允许列表内放行，实际 %d", code)
	}
}

// TestRequireRoleAllowsAdminAlways admin 为超级角色，恒放行。
func TestRequireRoleAllowsAdminAlways(t *testing.T) {
	if code := doRole(newRoleRouter("admin", "agent")); code != http.StatusOK {
		t.Fatalf("admin 应恒放行，实际 %d", code)
	}
}

// TestRequireRoleRejectsUnlistedRole 角色不在列表内 → 403（进入业务域前即拒绝）。
func TestRequireRoleRejectsUnlistedRole(t *testing.T) {
	if code := doRole(newRoleRouter("user", "admin", "agent")); code != http.StatusForbidden {
		t.Fatalf("user 访问坐席接口应 403，实际 %d", code)
	}
}

// TestRequireRoleSkipsWhenAuthDisabled 鉴权关闭（本地联调）时身份无角色，应跳过校验保持可用。
func TestRequireRoleSkipsWhenAuthDisabled(t *testing.T) {
	if code := doRole(newRoleRouter("", "admin")); code != http.StatusOK {
		t.Fatalf("无角色（鉴权关闭）应跳过角色校验，实际 %d", code)
	}
}

// TestRequireRoleRejectsMissingTenant 无租户上下文一律 401。
func TestRequireRoleRejectsMissingTenant(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.GET("/secret", RequireRole("admin"), func(c *gin.Context) { c.JSON(http.StatusOK, nil) })

	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/secret", nil))

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("缺少租户上下文应 401，实际 %d", rec.Code)
	}
}
