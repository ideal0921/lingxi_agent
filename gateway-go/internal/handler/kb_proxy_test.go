package handler

import (
	"bytes"
	"encoding/json"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

// newKBProxyTestServer 启动真实的 gin HTTP 服务（而非 httptest.ResponseRecorder）：
// httputil.ReverseProxy 会调用底层 ResponseWriter 的 CloseNotify，
// 而 ResponseRecorder 未实现该接口，故必须走真实服务器路径。
func newKBProxyTestServer(t *testing.T, javaBaseURL, route string) *httptest.Server {
	t.Helper()
	gin.SetMode(gin.TestMode)
	kbProxy, err := NewKBProxy(javaBaseURL)
	if err != nil {
		t.Fatalf("构造知识库代理失败: %v", err)
	}
	router := gin.New()
	router.Any(route, kbProxy)
	server := httptest.NewServer(router)
	t.Cleanup(server.Close)
	return server
}

// TestKBProxyForwardsMultipart 知识库代理必须原样转发 multipart（含 boundary）、查询串与令牌。
// 背景：知识库文档/分片上传为 multipart 表单，代理若缓冲或改写请求体会破坏 multipart 解析。
func TestKBProxyForwardsMultipart(t *testing.T) {
	var seenContentType, seenQuery, seenAuth string
	var seenBody []byte
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seenContentType = r.Header.Get("Content-Type")
		seenQuery = r.URL.RawQuery
		seenAuth = r.Header.Get("Authorization")
		// 用 ReadAll 而非按 ContentLength 预分配：代理可能以 chunked 转发（ContentLength 为 -1）
		seenBody, _ = io.ReadAll(r.Body)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"code":0,"message":"success","data":{"docId":"d-1"}}`))
	}))
	t.Cleanup(backend.Close)

	gateway := newKBProxyTestServer(t, backend.URL, "/api/v1/kb/documents")

	// 构造 multipart 请求体（模拟前端单请求上传）
	var buf bytes.Buffer
	writer := multipart.NewWriter(&buf)
	part, err := writer.CreateFormFile("file", "kb-doc.txt")
	if err != nil {
		t.Fatalf("构造表单失败: %v", err)
	}
	_, _ = part.Write([]byte("退款 3-7 个工作日到账"))
	_ = writer.Close()

	req, err := http.NewRequest(http.MethodPost,
		gateway.URL+"/api/v1/kb/documents?kbId=kb-1&title=docs", &buf)
	if err != nil {
		t.Fatalf("构造请求失败: %v", err)
	}
	req.Header.Set("Content-Type", writer.FormDataContentType())
	req.Header.Set("Authorization", "Bearer unit-token")

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	defer func() { _ = resp.Body.Close() }()
	body, _ := io.ReadAll(resp.Body)

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("代理应回传后端 200，实际 %d: %s", resp.StatusCode, string(body))
	}
	if !strings.HasPrefix(seenContentType, "multipart/form-data; boundary=") {
		t.Fatalf("必须保留 multipart Content-Type 与 boundary，实际 %q", seenContentType)
	}
	if seenQuery != "kbId=kb-1&title=docs" {
		t.Fatalf("查询串应原样转发，实际 %q", seenQuery)
	}
	if seenAuth != "Bearer unit-token" {
		t.Fatalf("令牌应透传给 Java 校验，实际 %q", seenAuth)
	}
	if !bytes.Contains(seenBody, []byte("kb-doc.txt")) {
		t.Fatal("请求体（含文件字段）应完整转发")
	}
	var payload map[string]any
	if err := json.Unmarshal(body, &payload); err != nil {
		t.Fatalf("响应应为可解析 JSON: %v", err)
	}
	if payload["code"] != float64(0) {
		t.Fatalf("响应体应原样回传（Result 信封），实际 %v", payload)
	}
}

// TestKBProxyReturns503WhenBackendDown 后端不可达时回 503（通道故障）并给出可读 JSON。
func TestKBProxyReturns503WhenBackendDown(t *testing.T) {
	gateway := newKBProxyTestServer(t, "http://127.0.0.1:1", "/api/v1/kb/bases")

	resp, err := http.Get(gateway.URL + "/api/v1/kb/bases")
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	defer func() { _ = resp.Body.Close() }()
	body, _ := io.ReadAll(resp.Body)

	if resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("后端不可达应 503，实际 %d", resp.StatusCode)
	}
	if !strings.Contains(string(body), "知识库服务暂不可用") {
		t.Fatalf("应回可读错误信息，实际 %s", string(body))
	}
}
