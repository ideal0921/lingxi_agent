package client

import (
	"context"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"

	sessionv1 "github.com/lingxi/gateway-go/proto/sessionv1"
)

// SessionService 会话域 gRPC 客户端：网关调用 Java 会话域的封装。
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

// CreateSession 建会话。
func (s *SessionService) CreateSession(userID, channel string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	resp, err := s.client.CreateSession(ctx, &sessionv1.CreateSessionRequest{
		UserId: userID, Channel: channel,
	})
	if err != nil {
		return "", err
	}
	return resp.SessionId, nil
}

// SendMessage 发送消息，返回 seq 等。
func (s *SessionService) SendMessage(sessionID, sender, senderID, content string) (*sessionv1.SendMessageResponse, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return s.client.SendMessage(ctx, &sessionv1.SendMessageRequest{
		SessionId: sessionID, Sender: sender, SenderId: senderID, Content: content,
	})
}

// PullMessages 断连续传：拉取 lastSeq 之后的遗漏消息。
func (s *SessionService) PullMessages(sessionID string, lastSeq int64, limit int32) ([]*sessionv1.SessionMessage, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	resp, err := s.client.PullMessages(ctx, &sessionv1.PullMessagesRequest{
		SessionId: sessionID, LastSeq: lastSeq, Limit: limit,
	})
	if err != nil {
		return nil, err
	}
	return resp.Messages, nil
}

// ListWaitingSessions 客服队列：待接管会话列表。
func (s *SessionService) ListWaitingSessions(limit int32) ([]*sessionv1.SessionSummary, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	resp, err := s.client.ListWaitingSessions(ctx, &sessionv1.ListWaitingSessionsRequest{Limit: limit})
	if err != nil {
		return nil, err
	}
	return resp.Sessions, nil
}

// TakeOver 客服接管：并发抢单由 Java 会话域保证仅成功一次。
func (s *SessionService) TakeOver(sessionID, agentID string) (*sessionv1.TakeOverResponse, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
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

// ListAgentSessions 指定坐席的会话列表。
func (s *SessionService) ListAgentSessions(agentID string) ([]*sessionv1.SessionSummary, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	resp, err := s.client.ListAgentSessions(ctx, &sessionv1.ListAgentSessionsRequest{AgentId: agentID})
	if err != nil {
		return nil, err
	}
	return resp.Sessions, nil
}

// Close 关闭连接。
func (s *SessionService) Close() error {
	return s.conn.Close()
}
