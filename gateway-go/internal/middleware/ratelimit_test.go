package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"golang.org/x/time/rate"

	"github.com/lingxi/gateway-go/internal/tenant"
)

// withIdentity 注入测试用租户身份，模拟鉴权中间件。
func withIdentity(tenantID string) gin.HandlerFunc {
	return func(c *gin.Context) {
		tenant.SetToGin(c, tenant.Identity{TenantID: tenantID})
		c.Next()
	}
}

// newTenantRateRouter 构造「注入身份 + 租户限流」的测试路由。
func newTenantRateRouter(tenantID string, defaultRPS, defaultBurst int, quotas map[string]TenantQuota) *gin.Engine {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(withIdentity(tenantID))
	router.Use(TenantRateLimit(defaultRPS, defaultBurst, quotas))
	router.GET("/ping", func(c *gin.Context) { c.JSON(http.StatusOK, gin.H{"ok": true}) })
	return router
}

func doGet(router *gin.Engine) int {
	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)
	return rec.Code
}

// TestTenantRateLimitRejectsOverQuota 默认配额下超额请求被 429 拒绝。
func TestTenantRateLimitRejectsOverQuota(t *testing.T) {
	router := newTenantRateRouter("tenant-a", 1, 1, nil)

	if code := doGet(router); code != http.StatusOK {
		t.Fatalf("首个请求应放行，实际 %d", code)
	}
	if code := doGet(router); code != http.StatusTooManyRequests {
		t.Fatalf("超出突发容量应 429，实际 %d", code)
	}
}

// TestTenantRateLimitIsolatesBigTenant 大租户独立配额：其它租户超额不影响大租户放行。
func TestTenantRateLimitIsolatesBigTenant(t *testing.T) {
	quotas := map[string]TenantQuota{"big-tenant": {RPS: 100, Burst: 20}}
	// 小租户先打满自身默认配额
	small := newTenantRateRouter("small-tenant", 1, 1, quotas)
	doGet(small)
	if code := doGet(small); code != http.StatusTooManyRequests {
		t.Fatalf("小租户超额应 429，实际 %d", code)
	}

	// 大租户使用独立桶：连续请求仍应放行（证明未与小租户共用额度）
	big := newTenantRateRouter("big-tenant", 1, 1, quotas)
	for i := 0; i < 10; i++ {
		if code := doGet(big); code != http.StatusOK {
			t.Fatalf("大租户独立配额内第 %d 次请求应放行，实际 %d", i+1, code)
		}
	}
}

// TestTenantRateLimitSkipsWithoutIdentity 无租户上下文时跳过租户限流（由 IP 限流兜底）。
func TestTenantRateLimitSkipsWithoutIdentity(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(TenantRateLimit(1, 1, nil))
	router.GET("/ping", func(c *gin.Context) { c.JSON(http.StatusOK, gin.H{"ok": true}) })
	for i := 0; i < 3; i++ {
		if code := doGet(router); code != http.StatusOK {
			t.Fatalf("无身份应跳过租户限流，第 %d 次实际 %d", i+1, code)
		}
	}
}

// TestKeyLimiterSweepsIdleEntries 空闲条目会被清扫，避免键空间无界增长（内存防护）。
func TestKeyLimiterSweepsIdleEntries(t *testing.T) {
	limiter := newKeyLimiter(10 * time.Millisecond)
	limiter.allow("k1", rate.Limit(1), 1)
	if len(limiter.clients) != 1 {
		t.Fatalf("首次调用应建桶，实际 %d", len(limiter.clients))
	}
	time.Sleep(15 * time.Millisecond)
	// 触发清扫（sweep 周期由 ttl 决定）
	limiter.allow("k2", rate.Limit(1), 1)
	if len(limiter.clients) != 1 {
		t.Fatalf("空闲桶应被清扫，实际 %d", len(limiter.clients))
	}
}

// TestIPRateLimitRejectsOverQuota IP 维度限流仍按客户端地址生效。
func TestIPRateLimitRejectsOverQuota(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(RateLimit(1, 1))
	router.GET("/ping", func(c *gin.Context) { c.JSON(http.StatusOK, gin.H{"ok": true}) })
	if code := doGet(router); code != http.StatusOK {
		t.Fatalf("首个请求应放行，实际 %d", code)
	}
	if code := doGet(router); code != http.StatusTooManyRequests {
		t.Fatalf("超限应 429，实际 %d", code)
	}
}
