package middleware

import (
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"golang.org/x/time/rate"

	"github.com/lingxi/gateway-go/internal/tenant"
)

// keyLimiter 按业务键（IP / 租户）维护独立令牌桶，防止单个键打满网关。
//
// 内存控制：条目记录最近使用时间，按 ttl 周期清扫空闲条目，
// 避免长期运行时键空间（尤其是租户维度）无界增长。
type keyLimiter struct {
	mu      sync.Mutex
	clients map[string]*clientEntry
	// ttl 空闲条目存活时长，兼作清扫周期。
	ttl       time.Duration
	lastSweep time.Time
}

type clientEntry struct {
	limiter  *rate.Limiter
	lastSeen time.Time
}

func newKeyLimiter(ttl time.Duration) *keyLimiter {
	return &keyLimiter{clients: make(map[string]*clientEntry), ttl: ttl, lastSweep: time.Now()}
}

// allow 按指定速率参数取桶并尝试放行；键首次出现时按传入参数建桶。
func (l *keyLimiter) allow(key string, r rate.Limit, burst int) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.sweepLocked()

	entry, ok := l.clients[key]
	if !ok {
		entry = &clientEntry{limiter: rate.NewLimiter(r, burst)}
		l.clients[key] = entry
	}
	entry.lastSeen = time.Now()
	return entry.limiter.Allow()
}

// sweepLocked 清理空闲超过 ttl 的条目（调用方需持有锁）。
func (l *keyLimiter) sweepLocked() {
	now := time.Now()
	if now.Sub(l.lastSweep) < l.ttl {
		return
	}
	for key, entry := range l.clients {
		if now.Sub(entry.lastSeen) > l.ttl {
			delete(l.clients, key)
		}
	}
	l.lastSweep = now
}

// RateLimit IP 维度令牌桶限流中间件，返回 429 并附带重试提示。
func RateLimit(rps int, burst int) gin.HandlerFunc {
	limiter := newKeyLimiter(10 * time.Minute)
	limit := rate.Limit(rps)
	return func(c *gin.Context) {
		if !limiter.allow(clientIP(c), limit, burst) {
			abortRateLimited(c, "请求过于频繁，请稍后重试")
		}
	}
}

// TenantQuota 租户独立限流配额（M5-5.5 大租户独立资源组）。
type TenantQuota struct {
	// RPS 每秒放行数。
	RPS int
	// Burst 突发容量。
	Burst int
}

// TenantRateLimit 租户维度令牌桶限流：大租户使用独立配额，不与其它租户争用同一桶。
//
// 与 IP 限流互补：IP 限流防单机恶意刷量，租户限流防单租户体量挤占全站。
func TenantRateLimit(defaultRPS, defaultBurst int, quotas map[string]TenantQuota) gin.HandlerFunc {
	limiter := newKeyLimiter(10 * time.Minute)
	return func(c *gin.Context) {
		identity, ok := tenant.FromGin(c)
		if !ok || !identity.HasTenant() {
			// 无租户上下文（如未挂鉴权中间件）：跳过租户限流，由 IP 限流兜底
			c.Next()
			return
		}
		// 每次请求按该租户配额取桶：首次出现即按独立配额建桶（大租户不与默认桶争用）
		rps, burst := defaultRPS, defaultBurst
		if quota, exists := quotas[identity.TenantID]; exists && quota.RPS > 0 {
			rps, burst = quota.RPS, maxInt(quota.Burst, 1)
		}
		if !limiter.allow(identity.TenantID, rate.Limit(rps), maxInt(burst, 1)) {
			abortRateLimited(c, "当前租户请求过于频繁，请稍后重试")
		}
	}
}

func abortRateLimited(c *gin.Context, message string) {
	c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{
		"code":    "RATE_LIMITED",
		"message": message,
	})
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func clientIP(c *gin.Context) string {
	// 直连场景取 RemoteAddr；经代理后按 X-Forwarded-For 首个可信地址。
	if xff := c.GetHeader("X-Forwarded-For"); xff != "" {
		if ip, _, err := net.SplitHostPort(xff); err == nil {
			return ip
		}
	}
	host, _, err := net.SplitHostPort(c.Request.RemoteAddr)
	if err != nil {
		return c.Request.RemoteAddr
	}
	return host
}
