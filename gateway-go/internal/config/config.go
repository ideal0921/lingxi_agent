// Package config 加载网关运行配置（全部环境变量化，无硬编码）。
package config

import "os"

// Config 网关运行配置。
type Config struct {
	// Port 监听地址，如 ":8080"。
	Port string
	// EnableAuth 是否开启鉴权中间件（M1 骨架阶段默认为 false）。
	EnableAuth bool
}

// Load 从环境变量加载配置，未设置时使用本地开发默认值。
func Load() *Config {
	return &Config{
		Port:       getEnv("GATEWAY_PORT", ":8080"),
		EnableAuth: getEnv("GATEWAY_ENABLE_AUTH", "false") == "true",
	}
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
