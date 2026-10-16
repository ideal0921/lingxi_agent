package handler

import (
	"net/http"
	"net/http/httputil"
	"net/url"

	"github.com/gin-gonic/gin"
)

// NewKBProxy 生成知识库 REST 的反向代理处理器（M5-5.6 6.5）。
//
// 为什么不用 JSON 透传器（Proxy）：知识库包含 **multipart 表单上传**（单片可达数 MB），
// 手写缓冲会把整包读入内存且需自行维护 multipart 边界；而 httputil.ReverseProxy
// 以流式转发并原样保留 Content-Type / boundary，内存占用与文件大小无关。
//
// 认证与授权由调用方在路由上挂载（网关鉴权 + admin 角色），下游 Java 侧再按 JWT 收口租户。
func NewKBProxy(javaBaseURL string) (gin.HandlerFunc, error) {
	target, err := url.Parse(javaBaseURL)
	if err != nil {
		return nil, err
	}
	proxy := httputil.NewSingleHostReverseProxy(target)

	// 默认 Director 已完成 scheme/host 改写；此处仅固定 Host 头，避免 Java 侧按 Host 路由异常
	originalDirector := proxy.Director
	proxy.Director = func(req *http.Request) {
		originalDirector(req)
		req.Host = target.Host
	}

	// 后端不可达时返回 503（通道故障），与业务拒绝（4xx）区分
	proxy.ErrorHandler = func(w http.ResponseWriter, _ *http.Request, err error) {
		_ = err
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(http.StatusServiceUnavailable)
		_, _ = w.Write([]byte(`{"code":503,"message":"知识库服务暂不可用"}`))
	}

	return func(c *gin.Context) {
		proxy.ServeHTTP(c.Writer, c.Request)
	}, nil
}
