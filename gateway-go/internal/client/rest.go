package client

import (
	"context"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/lingxi/gateway-go/internal/trace"
)

// RestForwarder 向 Java REST 域转发管理类请求（登录/用户/日志），是网关的「REST 透传」通道。
//
// 为什么用 REST 而非 gRPC：这些接口的权威契约（Result 结构、HTTP 状态语义）定义在 Java REST 层，
// 透传可避免为管理接口额外维护一套 proto；网关只做协议转换，不持有业务规则。
type RestForwarder struct {
	baseURL string
	client  *http.Client
}

// NewRestForwarder 创建透传器；baseURL 形如 http://127.0.0.1:8081。
func NewRestForwarder(baseURL string) *RestForwarder {
	return &RestForwarder{
		baseURL: strings.TrimRight(baseURL, "/"),
		// 管理接口为轻量交互，超时收紧到 10s，避免前端长时间挂起
		client: &http.Client{Timeout: 10 * time.Second},
	}
}

// ForwardRequest 一次透传所需的全部信息。
type ForwardRequest struct {
	Method        string
	Path          string // Java 侧完整路径，如 /api/v1/users/xxx/password
	RawQuery      string
	Body          []byte
	Authorization string // 原样透传，Java 侧自行校验
	ClientIP      string
	UserAgent     string
	// TraceID 链路 ID（M5-5.9）：透传为下游 `x-trace-id` 头，使 Java 段挂到同一条链。
	TraceID string
}

// ForwardResponse Java 侧响应（状态码与响应体原样回传）。
type ForwardResponse struct {
	Status      int
	Body        []byte
	ContentType string
}

// Forward 执行透传。**不解析响应体**：Java 返回的 Result 结构与状态语义直接透传给前端，
// 保证「业务拒绝（400/401/403/404/409）」与「通道故障（5xx）」在前端可区分。
func (f *RestForwarder) Forward(ctx context.Context, req ForwardRequest) (ForwardResponse, error) {
	target := f.baseURL + req.Path
	if req.RawQuery != "" {
		target += "?" + req.RawQuery
	}
	var bodyReader io.Reader
	if len(req.Body) > 0 {
		bodyReader = strings.NewReader(string(req.Body))
	}
	httpReq, err := http.NewRequestWithContext(ctx, req.Method, target, bodyReader)
	if err != nil {
		return ForwardResponse{}, err
	}
	httpReq.Header.Set("Content-Type", "application/json")
	if req.Authorization != "" {
		httpReq.Header.Set("Authorization", req.Authorization)
	}
	if req.ClientIP != "" {
		httpReq.Header.Set("X-Forwarded-For", req.ClientIP)
	}
	if req.UserAgent != "" {
		httpReq.Header.Set("User-Agent", req.UserAgent)
	}
	if req.TraceID != "" {
		httpReq.Header.Set(trace.HeaderTraceID, req.TraceID)
	}

	resp, err := f.client.Do(httpReq)
	if err != nil {
		return ForwardResponse{}, err
	}
	defer func() { _ = resp.Body.Close() }()
	payload, err := io.ReadAll(io.LimitReader(resp.Body, maxRestBodyBytes))
	if err != nil {
		return ForwardResponse{}, err
	}
	return ForwardResponse{
		Status:      resp.StatusCode,
		Body:        payload,
		ContentType: resp.Header.Get("Content-Type"),
	}, nil
}

// maxRestBodyBytes 透传响应体上限（1MB），防止异常大响应拖垮网关。
const maxRestBodyBytes = 1 << 20

// ParseQuery 解析原始查询串（透传时需剔除 token，避免令牌出现在下游日志）。
func ParseQuery(rawQuery string) url.Values {
	values, err := url.ParseQuery(rawQuery)
	if err != nil {
		return url.Values{}
	}
	return values
}
