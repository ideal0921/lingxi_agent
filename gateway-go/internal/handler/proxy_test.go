package handler

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"

	"github.com/lingxi/gateway-go/internal/client"
)

// newProxyRouter 构造指向指定后端地址的透传测试路由。
func newProxyRouter(t *testing.T, javaPath string) (*gin.Engine, *httptest.Server) {
	t.Helper()
	gin.SetMode(gin.TestMode)
	// 假 Java 后端：回显方法/路径/查询串/请求体，并检查令牌头
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/api/v1/auth/login" && r.Header.Get("Authorization") == "" {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"code":401,"message":"账号或密码错误"}`))
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]string{
			"path":   r.URL.Path,
			"query":  r.URL.RawQuery,
			"method": r.Method,
			"body":   string(body),
			"token":  r.Header.Get("Authorization"),
			"xff":    r.Header.Get("X-Forwarded-For"),
		})
	}))
	t.Cleanup(backend.Close)

	router := gin.New()
	proxy := NewProxy(client.NewRestForwarder(backend.URL))
	router.POST("/api/v1/users/:user_id/password", proxy.Forward(javaPath))
	router.GET("/api/v1/logs", proxy.Forward("/api/v1/logs"))
	router.Any("/api/v1/auth/login", proxy.Forward("/api/v1/auth/login"))
	return router, backend
}

// TestProxyReplacesPathParamsAndForwardsAuth 路径占位替换 + 令牌透传 + token 查询参数剔除。
func TestProxyReplacesPathParamsAndForwardsAuth(t *testing.T) {
	router, _ := newProxyRouter(t, "/api/v1/users/:user_id/password")

	req := httptest.NewRequest(http.MethodPost, "/api/v1/users/u-123/password?x=1&token=secret-token",
		strings.NewReader(`{"password":"NewPass@123"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer jwt-token")
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("透传应成功，实际 %d: %s", rec.Code, rec.Body.String())
	}
	var got map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("响应解析失败: %v", err)
	}
	if got["path"] != "/api/v1/users/u-123/password" {
		t.Fatalf("路径占位未替换: %s", got["path"])
	}
	if got["token"] != "Bearer jwt-token" {
		t.Fatalf("令牌应透传给 Java 侧校验: %s", got["token"])
	}
	if strings.Contains(got["query"], "token=") {
		t.Fatalf("token 查询参数不应进入下游请求（避免令牌落入下游日志）: %s", got["query"])
	}
	if !strings.Contains(got["query"], "x=1") {
		t.Fatalf("其余查询参数应保留: %s", got["query"])
	}
	if got["body"] != `{"password":"NewPass@123"}` {
		t.Fatalf("请求体应原样透传: %s", got["body"])
	}
	if got["xff"] == "" {
		t.Fatal("应透传来源 IP（供审计日志记录）")
	}
}

// TestProxyPassesThroughJavaStatusAndBody 状态码与响应体原样回传（业务拒绝不被网关改写）。
func TestProxyPassesThroughJavaStatusAndBody(t *testing.T) {
	router, _ := newProxyRouter(t, "/api/v1/auth/login")

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login", strings.NewReader(`{"username":"a","password":"b"}`))
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("应原样回传 401，实际 %d", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), "账号或密码错误") {
		t.Fatalf("应原样回传 Java 的消息体: %s", rec.Body.String())
	}
}

// TestProxyReturns503WhenBackendDown 后端不可达时返回 503（通道故障，非业务拒绝）。
func TestProxyReturns503WhenBackendDown(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	// 指向未监听端口，构造惰性失败
	proxy := NewProxy(client.NewRestForwarder("http://127.0.0.1:1"))
	router.GET("/api/v1/logs", proxy.Forward("/api/v1/logs"))

	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/logs", nil))

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("后端不可达应 503，实际 %d", rec.Code)
	}
}

// TestResolveJavaPathKeepsUnknownParams 未知占位（无对应路由参数）替换为空，不 panic。
func TestResolveJavaPathKeepsUnknownParams(t *testing.T) {
	gin.SetMode(gin.TestMode)
	_, _ = newProxyRouter(t, "/api/v1/users/:user_id/password")
	c, _ := gin.CreateTestContext(httptest.NewRecorder())
	if got := resolveJavaPath("/api/v1/users/:user_id/password", c); got != "/api/v1/users//password" {
		t.Fatalf("缺少参数时应替换为空串，实际 %q", got)
	}
}
