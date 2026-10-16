// Package config 加载网关运行配置（全部环境变量化，无硬编码）。
package config

import (
	"encoding/json"
	"log"
	"os"
	"strconv"
	"strings"

	"github.com/lingxi/gateway-go/internal/middleware"
)

// Config 网关运行配置。
type Config struct {
	// Port 监听地址，如 ":8080"。
	Port string
	// EnableAuth 是否开启鉴权中间件（生产必须开启；关闭时租户由默认值/联调头提供）。
	EnableAuth bool
	// JWTSecret JWT 签名密钥（HS256），仅从环境变量读取，禁止硬编码。
	JWTSecret string
	// SessionServiceAddr 会话域 gRPC 地址（Java 业务域）。
	SessionServiceAddr string
	// JavaRestAddr Java REST 地址（M5-5.6）：登录/用户/日志等管理接口透传目标。
	JavaRestAddr string
	// DefaultTenant 默认租户：鉴权关闭（本地联调）时的回落租户。
	DefaultTenant string

	// TenantRateRPS 租户级默认每秒放行数（M5-5.5 大租户独立资源组）。
	TenantRateRPS int
	// TenantRateBurst 租户级默认突发容量。
	TenantRateBurst int
	// TenantQuotas 大租户独立限流配额：租户 ID → 配额（环境变量 JSON）。
	TenantQuotas map[string]middleware.TenantQuota

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
	cfg := &Config{
		Port:               getEnv("GATEWAY_PORT", ":8080"),
		EnableAuth:         getEnv("GATEWAY_ENABLE_AUTH", "false") == "true",
		JWTSecret:          getEnv("GATEWAY_JWT_SECRET", ""),
		SessionServiceAddr: getEnv("GATEWAY_SESSION_SERVICE_ADDR", "localhost:9090"),
		JavaRestAddr:       getEnv("GATEWAY_JAVA_REST_ADDR", "http://127.0.0.1:8081"),
		DefaultTenant:      getEnv("GATEWAY_DEFAULT_TENANT", "lingxi"),
		TenantRateRPS:      getEnvInt("GATEWAY_TENANT_RATE_RPS", 100),
		TenantRateBurst:    getEnvInt("GATEWAY_TENANT_RATE_BURST", 50),
		TenantQuotas:       parseTenantQuotas(getEnv("GATEWAY_TENANT_QUOTAS", "")),
		KafkaEnabled:       getEnv("GATEWAY_KAFKA_ENABLED", "false") == "true",
		KafkaBrokers:       splitAndTrim(getEnv("GATEWAY_KAFKA_BROKERS", "localhost:9092")),
		KafkaChunkTopic:    getEnv("GATEWAY_KAFKA_CHUNK_TOPIC", "lingxi.ai.chunk"),
		KafkaSuggestTopic:  getEnv("GATEWAY_KAFKA_SUGGEST_TOPIC", "lingxi.ai.suggest.result"),
		KafkaGroupID:       getEnv("GATEWAY_KAFKA_GROUP_ID", "gateway-go"),
	}
	if cfg.EnableAuth && cfg.JWTSecret == "" {
		log.Fatal("已开启鉴权（GATEWAY_ENABLE_AUTH=true）但未配置 GATEWAY_JWT_SECRET，拒绝启动")
	}
	return cfg
}

// parseTenantQuotas 解析大租户独立配额 JSON，形如 {"big-tenant":{"rps":1000,"burst":500}}。
// 解析失败仅告警并退回空配额（不影响启动，租户默认配额仍生效）。
func parseTenantQuotas(raw string) map[string]middleware.TenantQuota {
	if strings.TrimSpace(raw) == "" {
		return map[string]middleware.TenantQuota{}
	}
	quotas := map[string]middleware.TenantQuota{}
	if err := json.Unmarshal([]byte(raw), &quotas); err != nil {
		log.Printf("[warn] GATEWAY_TENANT_QUOTAS 解析失败，改用默认租户配额: %v", err)
		return map[string]middleware.TenantQuota{}
	}
	return quotas
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

// getEnvInt 读取整型环境变量，非法值回落到默认值（配置错误不应导致启动失败）。
func getEnvInt(key string, fallback int) int {
	raw := os.Getenv(key)
	if raw == "" {
		return fallback
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value <= 0 {
		log.Printf("[warn] %s 非法（%q），使用默认值 %d", key, raw, fallback)
		return fallback
	}
	return value
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
