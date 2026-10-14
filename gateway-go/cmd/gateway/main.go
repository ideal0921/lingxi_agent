// gateway-go 网关启动入口。
// 职责边界：仅做协议转换、流量治理与消息转发，不含业务逻辑。
package main

import (
	"context"
	"log"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/lingxi/gateway-go/internal/client"
	"github.com/lingxi/gateway-go/internal/config"
	"github.com/lingxi/gateway-go/internal/handler"
	"github.com/lingxi/gateway-go/internal/kafka"
	"github.com/lingxi/gateway-go/internal/middleware"
	"github.com/lingxi/gateway-go/internal/ws"
)

func main() {
	cfg := config.Load()

	// 会话域 gRPC 客户端（Java 业务域）。
	sessionClient, err := client.NewSessionService(cfg.SessionServiceAddr)
	if err != nil {
		log.Fatalf("连接会话域失败: %v", err)
	}
	defer func() { _ = sessionClient.Close() }()

	// 工单域 gRPC 客户端（Java 业务域，共用地址）。
	ticketClient, err := client.NewTicketService(cfg.SessionServiceAddr)
	if err != nil {
		log.Fatalf("连接工单域失败: %v", err)
	}
	defer func() { _ = ticketClient.Close() }()

	router := gin.New()
	router.Use(gin.Recovery(), middleware.AccessLog())

	// 长连接：WSS 会话通道（不套限流，由心跳/缓冲控流）。
	hub := ws.NewHub()
	wsHandler := ws.NewHandler(hub, sessionClient)
	router.GET("/ws", wsHandler.ServeWS)

	// AI 流式片段消费（M3-3.8）：消费 Kafka chunk 事件并广播至会话连接。
	// 停机由 defer cancel 触发消费者正常退出（FetchMessage 返回 ctx 取消）。
	if cfg.KafkaEnabled {
		consumer := kafka.NewConsumer(cfg.KafkaBrokers, cfg.KafkaChunkTopic, cfg.KafkaGroupID, hub)
		consumeCtx, cancelConsume := context.WithCancel(context.Background())
		defer cancelConsume()
		go consumer.Run(consumeCtx)
	}

	api := router.Group("/api/v1")
	api.Use(middleware.RateLimit(100, 50)) // 默认 100 QPS/IP
	if cfg.EnableAuth {
		api.Use(middleware.Auth())
	}
	api.GET("/health", handler.Health)
	// 用户端建会话（返回 session_id 供 WSS 连接）。
	api.POST("/session", wsHandler.CreateSession)
	// 坐席回复下行接口（M2-B 由客服工作台调用）。
	api.POST("/agent/reply", wsHandler.PushAgentReply)

	// 客服工作台接口（M2-B）：队列/接管/会话列表/工单。
	agentHandler := handler.NewAgentHandler(sessionClient, ticketClient)
	api.GET("/agent/queue", agentHandler.ListQueue)
	api.POST("/agent/takeover", agentHandler.TakeOver)
	api.GET("/agent/sessions", agentHandler.ListAgentSessions)
	api.POST("/agent/tickets", agentHandler.CreateTicket)
	api.GET("/agent/tickets", agentHandler.ListTickets)
	api.POST("/agent/tickets/:ticket_id/status", agentHandler.UpdateTicketStatus)

	srv := &http.Server{
		Addr:              cfg.Port,
		Handler:           router,
		ReadHeaderTimeout: 5 * time.Second,
	}
	log.Printf("gateway-go listening on %s (session-service: %s)", cfg.Port, cfg.SessionServiceAddr)
	if err := srv.ListenAndServe(); err != nil {
		log.Fatalf("网关启动失败: %v", err)
	}
}
