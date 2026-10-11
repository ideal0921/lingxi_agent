// gateway-go 网关启动入口。
// 职责边界：仅做协议转换、流量治理与消息转发，不含业务逻辑。
package main

import (
	"log"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/lingxi/gateway-go/internal/config"
	"github.com/lingxi/gateway-go/internal/handler"
	"github.com/lingxi/gateway-go/internal/middleware"
)

func main() {
	cfg := config.Load()

	router := gin.New()
	router.Use(gin.Recovery(), middleware.AccessLog())

	api := router.Group("/api/v1")
	api.Use(middleware.RateLimit(100, 50)) // 默认 100 QPS/IP
	if cfg.EnableAuth {
		api.Use(middleware.Auth())
	}
	api.GET("/health", handler.Health)

	srv := &http.Server{
		Addr:              cfg.Port,
		Handler:           router,
		ReadHeaderTimeout: 5 * time.Second,
	}
	log.Printf("gateway-go listening on %s", cfg.Port)
	if err := srv.ListenAndServe(); err != nil {
		log.Fatalf("网关启动失败: %v", err)
	}
}
