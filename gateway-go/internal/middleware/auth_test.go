package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"

	"github.com/lingxi/gateway-go/internal/tenant"
)

const testJWTSecret = "middleware-test-secret"

// signJWT 生成测试用 HS256 令牌。
func signJWT(t *testing.T, claims jwt.MapClaims) string {
	t.Helper()
	signed, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString([]byte(testJWTSecret))
	if err != nil {
		t.Fatalf("签发测试令牌失败: %v", err)
	}
	return signed
}

// newAuthRouter 构造「鉴权中间件 + 回显身份」的测试路由。
func newAuthRouter(enableAuth bool, secret, defaultTenant string) *gin.Engine {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(Auth(enableAuth, secret, defaultTenant))
	router.GET("/whoami", func(c *gin.Context) {
		identity, ok := tenant.FromGin(c)
		if !ok {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "未注入身份"})
			return
		}
		c.JSON(http.StatusOK, gin.H{"tenant_id": identity.TenantID, "user_id": identity.UserID, "role": identity.Role})
	})
	return router
}

func doAuth(router *gin.Engine, authorization string, headers map[string]string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodGet, "/whoami", nil)
	if authorization != "" {
		req.Header.Set("Authorization", authorization)
	}
	for key, value := range headers {
		req.Header.Set(key, value)
	}
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)
	return rec
}

// TestAuthEnabledRequiresValidToken 鉴权开启时缺令牌/非法令牌一律 401。
func TestAuthEnabledRequiresValidToken(t *testing.T) {
	router := newAuthRouter(true, testJWTSecret, "lingxi")

	if rec := doAuth(router, "", nil); rec.Code != http.StatusUnauthorized {
		t.Fatalf("缺 Authorization 应 401，实际 %d", rec.Code)
	}
	if rec := doAuth(router, "Basic abc", nil); rec.Code != http.StatusUnauthorized {
		t.Fatalf("非 Bearer 应 401，实际 %d", rec.Code)
	}
	if rec := doAuth(router, "Bearer not-a-jwt", nil); rec.Code != http.StatusUnauthorized {
		t.Fatalf("非法令牌应 401，实际 %d", rec.Code)
	}
}

// TestAuthEnabledInjectsClaims 鉴权开启时注入令牌中的租户身份。
func TestAuthEnabledInjectsClaims(t *testing.T) {
	router := newAuthRouter(true, testJWTSecret, "lingxi")
	raw := signJWT(t, jwt.MapClaims{
		"tid": "tenant-jwt", "sub": "u-9", "role": "agent",
		"exp": time.Now().Add(time.Hour).Unix(),
	})

	rec := doAuth(router, "Bearer "+raw, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("合法令牌应放行，实际 %d: %s", rec.Code, rec.Body.String())
	}
	if body := rec.Body.String(); body != `{"role":"agent","tenant_id":"tenant-jwt","user_id":"u-9"}` {
		t.Fatalf("注入身份不符: %s", body)
	}
}

// TestAuthEnabledRejectsTokenWithoutTenantClaim 令牌无租户声明时拒绝（不回落到默认租户）。
func TestAuthEnabledRejectsTokenWithoutTenantClaim(t *testing.T) {
	router := newAuthRouter(true, testJWTSecret, "lingxi")
	raw := signJWT(t, jwt.MapClaims{"sub": "u-9", "exp": time.Now().Add(time.Hour).Unix()})

	if rec := doAuth(router, "Bearer "+raw, nil); rec.Code != http.StatusUnauthorized {
		t.Fatalf("缺租户声明应 401，实际 %d", rec.Code)
	}
}

// TestAuthDisabledUsesLocalDevHeaders 鉴权关闭（本地联调）时支持 X-Tenant-Id 指定租户。
func TestAuthDisabledUsesLocalDevHeaders(t *testing.T) {
	router := newAuthRouter(false, "", "lingxi")

	rec := doAuth(router, "", nil)
	if rec.Code != http.StatusOK || rec.Body.String() != `{"role":"","tenant_id":"lingxi","user_id":""}` {
		t.Fatalf("缺联调头应回落默认租户，实际 %d: %s", rec.Code, rec.Body.String())
	}

	rec = doAuth(router, "", map[string]string{
		"X-Tenant-Id": "tenant-local", "X-User-Id": "u-local", "X-Role": "agent",
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("联调头应放行，实际 %d", rec.Code)
	}
	if body := rec.Body.String(); body != `{"role":"agent","tenant_id":"tenant-local","user_id":"u-local"}` {
		t.Fatalf("联调头注入不符: %s", body)
	}
}
