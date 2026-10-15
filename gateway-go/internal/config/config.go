// Package config 加载网关运行配置（全部环境变量化，无硬编码）。
package config

import (
	"os"
	"strings"
)

// Config 网关运行配置。
type Config struct {
	// Port 监听地址，如 ":8080"。
	Port string
	// EnableAuth 是否开启鉴权中间件（M1 骨架阶段默认为 false）。
	EnableAuth bool
	// SessionServiceAddr 会话域 gRPC 地址（Java 业务域）。
	SessionServiceAddr string
	// DefaultTenant 默认租户：建会话请求未带 tenant_id 时的回落值
	// （生产由鉴权中间件按 JWT 注入真实租户，本地联调走默认租户）。
	DefaultTenant string

	// KafkaEnabled 是否启用 AI 流式片段消费（M3-3.8）；关闭时网关不连 Kafka。
	KafkaEnabled bool
	// KafkaBrokers 集群地址列表（逗号分隔环境变量解析而来）。
	KafkaBrokers []string
	// KafkaChunkTopic AI 流式片段主题。
	KafkaChunkTopic string
	// KafkaSuggestTopic 快捷回复建议异步结果主题（M4-4.6 过载降级）。
	KafkaSuggestTopic string
	// KafkaGroupID 消费组（多网关实例同组共享分区）。
	KafkaGroupID string
}

// Load 从环境变量加载配置，未设置时使用本地开发默认值。
func Load() *Config {
	return &Config{
		Port:               getEnv("GATEWAY_PORT", ":8080"),
		EnableAuth:         getEnv("GATEWAY_ENABLE_AUTH", "false") == "true",
		SessionServiceAddr: getEnv("GATEWAY_SESSION_SERVICE_ADDR", "localhost:9090"),
		DefaultTenant:      getEnv("GATEWAY_DEFAULT_TENANT", "lingxi"),
		KafkaEnabled:       getEnv("GATEWAY_KAFKA_ENABLED", "false") == "true",
		KafkaBrokers:       splitAndTrim(getEnv("GATEWAY_KAFKA_BROKERS", "localhost:9092")),
		KafkaChunkTopic:    getEnv("GATEWAY_KAFKA_CHUNK_TOPIC", "lingxi.ai.chunk"),
		KafkaSuggestTopic:  getEnv("GATEWAY_KAFKA_SUGGEST_TOPIC", "lingxi.ai.suggest.result"),
		KafkaGroupID:       getEnv("GATEWAY_KAFKA_GROUP_ID", "gateway-go"),
	}
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

// splitAndTrim 解析逗号分隔的多地址环境变量，剔除空白项。
func splitAndTrim(value string) []string {
	parts := strings.Split(value, ",")
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		if trimmed := strings.TrimSpace(part); trimmed != "" {
			out = append(out, trimmed)
		}
	}
	return out
}
