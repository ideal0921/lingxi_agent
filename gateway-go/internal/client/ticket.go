package client

import (
	"context"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"

	ticketv1 "github.com/lingxi/gateway-go/proto/ticketv1"
)

// TicketService 工单域 gRPC 客户端：网关转发工作台工单操作到 Java 工单域。
type TicketService struct {
	conn   *grpc.ClientConn
	client ticketv1.TicketServiceClient
}

// NewTicketService 建立到 Java 工单域的 gRPC 连接（与会话域共用地址）。
func NewTicketService(addr string) (*TicketService, error) {
	conn, err := grpc.NewClient(addr,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
	)
	if err != nil {
		return nil, err
	}
	return &TicketService{
		conn:   conn,
		client: ticketv1.NewTicketServiceClient(conn),
	}, nil
}

// CreateTicket 一键建单。
func (t *TicketService) CreateTicket(tenantID, sessionID, userID, agentID, title, description string) (*ticketv1.Ticket, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	resp, err := t.client.CreateTicket(ctx, &ticketv1.CreateTicketRequest{
		TenantId: tenantID, SessionId: sessionID, UserId: userID,
		AgentId: agentID, Title: title, Description: description,
	})
	if err != nil {
		return nil, err
	}
	return resp.Ticket, nil
}

// UpdateTicketStatus 工单状态流转（status 为 proto 枚举值）。
func (t *TicketService) UpdateTicketStatus(ticketID string, status ticketv1.TicketStatus, operator string) (*ticketv1.UpdateTicketStatusResponse, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return t.client.UpdateTicketStatus(ctx, &ticketv1.UpdateTicketStatusRequest{
		TicketId: ticketID, TargetStatus: status, Operator: operator,
	})
}

// ListTickets 工单列表（agentID 或 status 过滤；status 为空时传 0 值枚举）。
func (t *TicketService) ListTickets(agentID string, status ticketv1.TicketStatus) ([]*ticketv1.Ticket, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	resp, err := t.client.ListTickets(ctx, &ticketv1.ListTicketsRequest{
		AgentId: agentID, Status: status,
	})
	if err != nil {
		return nil, err
	}
	return resp.Tickets, nil
}

// Close 关闭连接。
func (t *TicketService) Close() error {
	return t.conn.Close()
}
