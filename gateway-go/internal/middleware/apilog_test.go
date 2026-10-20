package middleware

import (
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"

	"github.com/gin-gonic/gin"

	"github.com/lingxi/gateway-go/internal/tenant"
	"github.com/lingxi/gateway-go/internal/trace"
)

// captureRecorder 采集 span 的桩记录器。
type captureRecorder struct {
	mu    sync.Mutex
	spans []trace.Span
}

func (r *captureRecorder) Record(span trace.Span) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.spans = append(r.spans, span)
}

func (r *captureRecorder) snapshot() []trace.Span {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]trace.Span(nil), r.spans...)
}

// newApiLogRouter 构造挂载 ApiLog 的测试路由，并注入可信租户身份模拟鉴权中间件。
func newApiLogRouter(recorder trace.Recorder) *gin.Engine {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(func(c *gin.Context) {
		tenant.SetToGin(c, tenant.Identity{TenantID: "t1", UserID: "u1", Role: "admin"})
		c.Next()
	})
	router.Use(ApiLog(recorder, "lingxi-test"))
	router.GET("/api/v1/logs", func(c *gin.Context) { c.JSON(http.StatusOK, gin.H{"ok": true}) })
	router.GET("/api/v1/health", func(c *gin.Context) { c.JSON(http.StatusOK, gin.H{"status": "UP"}) })
	return router
}

// TestApiLogReusesInboundTraceID 入站已带 x-trace-id 时复用，并回写响应头。
func TestApiLogReusesInboundTraceID(t *testing.T) {
	recorder := &captureRecorder{}
	router := newApiLogRouter(recorder)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/logs", nil)
	req.Header.Set(trace.HeaderTraceID, "inbound-trace-1")
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)

	if got := rec.Header().Get(trace.ResponseHeaderTraceID); got != "inbound-trace-1" {
		t.Fatalf("响应头应回写入站链路 ID，实际 %q", got)
	}
	spans := recorder.snapshot()
	if len(spans) != 1 {
		t.Fatalf("应记录 1 条 span，实际 %d", len(spans))
	}
	span := spans[0]
	if span.TraceID != "inbound-trace-1" {
		t.Fatalf("span 应复用入站链路 ID，实际 %q", span.TraceID)
	}
	if span.Service != trace.ServiceGateway || span.Component != trace.ComponentHTTP {
		t.Fatalf("span 服务/组件不符: %+v", span)
	}
	if span.Entry != "/api/v1/logs" || span.HTTPMethod != http.MethodGet {
		t.Fatalf("span 入口/方法不符: %+v", span)
	}
	if span.Status != trace.StatusOK || span.StatusCode != http.StatusOK {
		t.Fatalf("span 状态不符: %+v", span)
	}
	if span.TenantID != "t1" || span.UserID != "u1" || span.Role != "admin" {
		t.Fatalf("span 应携带鉴权注入的租户身份: %+v", span)
	}
}

// TestApiLogGeneratesTraceIDWhenAbsent 入站未带 x-trace-id 时网关生成，并回写响应头。
func TestApiLogGeneratesTraceIDWhenAbsent(t *testing.T) {
	recorder := &captureRecorder{}
	router := newApiLogRouter(recorder)

	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/logs", nil))

	headerID := rec.Header().Get(trace.ResponseHeaderTraceID)
	if headerID == "" {
		t.Fatal("缺省时应生成并回写链路 ID")
	}
	spans := recorder.snapshot()
	if len(spans) != 1 {
		t.Fatalf("应记录 1 条 span，实际 %d", len(spans))
	}
	if spans[0].TraceID != headerID {
		t.Fatalf("span 与响应头链路 ID 应一致: span=%q header=%q", spans[0].TraceID, headerID)
	}
}

// TestApiLogSkipsHealth 探活接口不产生 span，也不回写链路 ID。
func TestApiLogSkipsHealth(t *testing.T) {
	recorder := &captureRecorder{}
	router := newApiLogRouter(recorder)

	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/health", nil))

	if len(recorder.snapshot()) != 0 {
		t.Fatal("探活接口不应产生 span")
	}
	if rec.Header().Get(trace.ResponseHeaderTraceID) != "" {
		t.Fatal("探活接口不应回写链路 ID")
	}
}

// TestApiLogMarksErrorStatus 4xx/5xx 记为 ERROR 并附错误摘要。
func TestApiLogMarksErrorStatus(t *testing.T) {
	recorder := &captureRecorder{}
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(ApiLog(recorder, "lingxi-test"))
	router.GET("/api/v1/logs", func(c *gin.Context) { c.Status(http.StatusForbidden) })

	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/logs", nil))

	spans := recorder.snapshot()
	if len(spans) != 1 {
		t.Fatalf("应记录 1 条 span，实际 %d", len(spans))
	}
	if spans[0].Status != trace.StatusError || spans[0].StatusCode != http.StatusForbidden {
		t.Fatalf("403 应记为 ERROR，实际 %+v", spans[0])
	}
}

// TestApiLogFallsBackToDefaultTenant 未认证请求（无鉴权中间件注入身份）回落默认租户，
// 保证登录等豁免路径的 span 不会因租户为空而在管理端被租户强过滤排除。
func TestApiLogFallsBackToDefaultTenant(t *testing.T) {
	recorder := &captureRecorder{}
	router := newApiLogRouterWithoutAuth(recorder)

	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/api/v1/auth/login", nil))

	spans := recorder.snapshot()
	if len(spans) != 1 {
		t.Fatalf("应记录 1 条 span，实际 %d", len(spans))
	}
	if spans[0].TenantID != "lingxi-test" {
		t.Fatalf("未认证请求应回落默认租户，实际 %q", spans[0].TenantID)
	}
	if spans[0].UserID != "" || spans[0].Role != "" {
		t.Fatalf("未认证请求不应有主体身份，实际 %+v", spans[0])
	}
}

// newApiLogRouterWithoutAuth 构造不带鉴权注入的路由：模拟登录等豁免鉴权的路径。
func newApiLogRouterWithoutAuth(recorder trace.Recorder) *gin.Engine {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(ApiLog(recorder, "lingxi-test"))
	router.POST("/api/v1/auth/login", func(c *gin.Context) { c.JSON(http.StatusOK, gin.H{"ok": true}) })
	return router
}
