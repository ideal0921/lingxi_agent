package client

import (
	"context"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"

	sessionv1 "github.com/lingxi/gateway-go/proto/sessionv1"
)

// SessionService 会话域 gRPC 客户端：网关调用 Java 会话域的封装。
//
// M5-5.5：所有方法首参为调用方上下文，Java 侧据此从 metadata 读取可信租户；
// 上下文由处理器用 tenant.ClientContext(c) 构造（携带 x-tenant-id/x-user-id/x-role）。
type SessionService struct {
	conn   *grpc.ClientConn
	client sessionv1.SessionServiceClient
}

// NewSessionService 建立到 Java 会话域的 gRPC 连接。
func NewSessionService(addr string) (*SessionService, error) {
	conn, err := grpc.NewClient(addr,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
	)
	if err != nil {
		return nil, err
	}
	return &SessionService{
		conn:   conn,
		client: sessionv1.NewSessionServiceClient(conn),
	}, nil
}

// CreateSession 建会话：租户以 metadata 中的可信租户为准，tenantID 仅为兼容字段。
func (s *SessionService) CreateSession(ctx context.Context, userID, channel, tenantID string) (string, error) {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	resp, err := s.client.CreateSession(ctx, &sessionv1.CreateSessionRequest{
		UserId: userID, Channel: channel, TenantId: tenantID,
	})
	if err != nil {
		return "", err
	}
	return resp.SessionId, nil
}

// SendMessage 发送消息，返回 seq 等。
func (s *SessionService) SendMessage(ctx context.Context, sessionID, sender, senderID, content string) (*sessionv1.SendMessageResponse, error) {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	return s.client.SendMessage(ctx, &sessionv1.SendMessageRequest{
		SessionId: sessionID, Sender: sender, SenderId: senderID, Content: content,
	})
}

// PullMessages 断连续传：拉取 lastSeq 之后的遗漏消息。
func (s *SessionService) PullMessages(ctx context.Context, sessionID string, lastSeq int64, limit int32) ([]*sessionv1.SessionMessage, error) {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	resp, err := s.client.PullMessages(ctx, &sessionv1.PullMessagesRequest{
		SessionId: sessionID, LastSeq: lastSeq, Limit: limit,
	})
	if err != nil {
		return nil, err
	}
	return resp.Messages, nil
}

// ListWaitingSessions 客服队列：待接管会话列表（Java 侧按调用方租户过滤）。
func (s *SessionService) ListWaitingSessions(ctx context.Context, limit int32) ([]*sessionv1.SessionSummary, error) {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	resp, err := s.client.ListWaitingSessions(ctx, &sessionv1.ListWaitingSessionsRequest{Limit: limit})
	if err != nil {
		return nil, err
	}
	return resp.Sessions, nil
}

// TakeOver 客服接管：并发抢单由 Java 会话域保证仅成功一次（含租户归属校验）。
func (s *SessionService) TakeOver(ctx context.Context, sessionID, agentID string) (*sessionv1.TakeOverResponse, error) {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	return s.client.TakeOver(ctx, &sessionv1.TakeOverRequest{SessionId: sessionID, AgentId: agentID})
}

// SuggestReplies 快捷回复建议（坐席工作台触发，同步通道）。
// 过载时 Java 侧可能返回 degraded=true, queued=true，结果稍后经 Kafka 异步推送。
func (s *SessionService) SuggestReplies(ctx context.Context, req *sessionv1.SuggestRepliesRequest) (*sessionv1.SuggestRepliesResponse, error) {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	return s.client.SuggestReplies(ctx, req)
}

// ListAgentSessions 指定坐席的会话列表（Java 侧按调用方租户过滤）。
func (s *SessionService) ListAgentSessions(ctx context.Context, agentID string) ([]*sessionv1.SessionSummary, error) {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	resp, err := s.client.ListAgentSessions(ctx, &sessionv1.ListAgentSessionsRequest{AgentId: agentID})
	if err != nil {
		return nil, err
	}
	return resp.Sessions, nil
}

// ListSessionsByUser 列「我」的历史会话（按最近活跃倒序）。
//
// 租户与用户均取自调用方上下文 metadata，**请求体不含 user_id/tenant_id**——
// 否则可借参数枚举他人会话（与 M5-5.5 的租户隔离约定一致）。
func (s *SessionService) ListSessionsByUser(ctx context.Context, limit int32) ([]*sessionv1.SessionListItem, error) {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	resp, err := s.client.ListSessionsByUser(ctx, &sessionv1.ListSessionsByUserRequest{Limit: limit})
	if err != nil {
		return nil, err
	}
	return resp.Sessions, nil
}

// SubmitFeedback 提交会话满意度评价（M5-5.1）。
// 租户/用户由 Java 会话域按会话解析，故前端不得传 tenant_id（防跨租户伪造）；
// Java 侧另校验会话是否归属调用方租户（M5-5.5）。
// 评分越界或会话不存在时以 ok=false + reason 返回（业务拒绝，非通道故障）。
func (s *SessionService) SubmitFeedback(ctx context.Context, sessionID string, rating int32, comment, tags string) (*sessionv1.SubmitFeedbackResponse, error) {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	return s.client.SubmitFeedback(ctx, &sessionv1.SubmitFeedbackRequest{
		SessionId: sessionID, Rating: rating, Comment: comment, Tags: tags,
	})
}

// Close 关闭连接。
func (s *SessionService) Close() error {
	return s.conn.Close()
}
