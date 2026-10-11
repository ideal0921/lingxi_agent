package client

import (
	"context"
	"time"

	healthv1 "github.com/lingxi/gateway-go/proto"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

// PingJava 通过 gRPC 调用 Java 业务域的 HealthService，用于跨语言链路验收。
// addr 形如 "localhost:9090"；超时由调用方控制。
func PingJava(ctx context.Context, addr string) (*healthv1.PingResponse, error) {
	conn, err := grpc.NewClient(addr,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithBlock(),
	)
	if err != nil {
		return nil, err
	}
	defer func() { _ = conn.Close() }()

	client := healthv1.NewHealthServiceClient(conn)
	return client.Ping(ctx, &healthv1.PingRequest{Service: "gateway-go"})
}

// PingJavaWithTimeout 带超时的便捷封装。
func PingJavaWithTimeout(addr string, timeout time.Duration) (*healthv1.PingResponse, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	return PingJava(ctx, addr)
}
