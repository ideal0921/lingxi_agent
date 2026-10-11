package middleware

import (
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"golang.org/x/time/rate"
)

// ipLimiter 按客户端 IP 维护独立限流器，防止单 IP 打满网关。
type ipLimiter struct {
	mu      sync.Mutex
	clients map[string]*rate.Limiter
	// rate 每秒放行数；burst 突发容量。
	rate  rate.Limit
	burst int
	// 清理过期限流器的空闲阈值。
	expire time.Duration
}

func newIPLimiter(r rate.Limit, burst int) *ipLimiter {
	return &ipLimiter{
		clients: make(map[string]*rate.Limiter),
		rate:    r,
		burst:   burst,
		expire:  10 * time.Minute,
	}
}

func (l *ipLimiter) get(ip string) *rate.Limiter {
	l.mu.Lock()
	defer l.mu.Unlock()
	limiter, ok := l.clients[ip]
	if !ok {
		limiter = rate.NewLimiter(l.rate, l.burst)
		l.clients[ip] = limiter
	}
	return limiter
}

// RateLimit 令牌桶限流中间件，返回 429 并附带重试提示。
func RateLimit(rps int, burst int) gin.HandlerFunc {
	limiter := newIPLimiter(rate.Limit(rps), burst)
	return func(c *gin.Context) {
		ip := clientIP(c)
		if !limiter.get(ip).Allow() {
			c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{
				"code":    "RATE_LIMITED",
				"message": "请求过于频繁，请稍后重试",
			})
			return
		}
		c.Next()
	}
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
