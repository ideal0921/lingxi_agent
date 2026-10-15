package client

import (
	"context"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"

	reportv1 "github.com/lingxi/gateway-go/proto/reportv1"
)

// 报表域调用超时：报表为聚合查询，单次耗时高于普通业务调用。
const reportTimeout = 5 * time.Second

// ReportService 报表域 gRPC 客户端：网关调用 Java 报表域的封装。
type ReportService struct {
	conn   *grpc.ClientConn
	client reportv1.ReportServiceClient
}

// NewReportService 建立到 Java 报表域的 gRPC 连接。
func NewReportService(addr string) (*ReportService, error) {
	conn, err := grpc.NewClient(addr,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
	)
	if err != nil {
		return nil, err
	}
	return &ReportService{
		conn:   conn,
		client: reportv1.NewReportServiceClient(conn),
	}, nil
}

// GetFunnelReport 会话漏斗：各阶段会话数与相邻转化率。
func (s *ReportService) GetFunnelReport(tenantID string, fromMs, toMs int64) (*reportv1.FunnelReport, error) {
	ctx, cancel := context.WithTimeout(context.Background(), reportTimeout)
	defer cancel()
	return s.client.GetFunnelReport(ctx, &reportv1.ReportRange{
		TenantId: tenantID, FromMs: fromMs, ToMs: toMs,
	})
}

// GetTokenReport 按日/提供方/模型的 Token 消耗明细与合计。
func (s *ReportService) GetTokenReport(tenantID string, fromMs, toMs int64) (*reportv1.TokenReport, error) {
	ctx, cancel := context.WithTimeout(context.Background(), reportTimeout)
	defer cancel()
	return s.client.GetTokenReport(ctx, &reportv1.ReportRange{
		TenantId: tenantID, FromMs: fromMs, ToMs: toMs,
	})
}

// GetCsatReport 满意度：评价总数、平均分、CSAT 与评分分布。
func (s *ReportService) GetCsatReport(tenantID string, fromMs, toMs int64) (*reportv1.CsatReport, error) {
	ctx, cancel := context.WithTimeout(context.Background(), reportTimeout)
	defer cancel()
	return s.client.GetCsatReport(ctx, &reportv1.ReportRange{
		TenantId: tenantID, FromMs: fromMs, ToMs: toMs,
	})
}

// Close 关闭连接。
func (s *ReportService) Close() error {
	return s.conn.Close()
}
