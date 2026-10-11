package client

import (
	"testing"
	"time"
)

// TestPingJava 集成验收：网关（Go）→ Java 域（gRPC 9090）链路连通。
// 前置条件：core-java 的 lingxi-start 服务已启动（含 gRPC 服务端）。
// 独立运行时跳过（不依赖外部服务）。
func TestPingJava(t *testing.T) {
	const javaAddr = "localhost:9090"

	resp, err := PingJavaWithTimeout(javaAddr, 3*time.Second)
	if err != nil {
		t.Skipf("Java gRPC 服务未就绪，跳过链路验证：%v", err)
	}

	if resp.GetStatus() != "ok" {
		t.Fatalf("期望 status=ok，实际 %q", resp.GetStatus())
	}
	if resp.GetService() != "core-java" {
		t.Fatalf("期望 service=core-java，实际 %q", resp.GetService())
	}
}
