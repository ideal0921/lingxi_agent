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
	"github.com/lingxi/gateway-go/internal/trace"
	"github.com/lingxi/gateway-go/internal/ws"
)

func main() {
	cfg := config.Load()

	// 链路追踪写入器（M5-5.9）：旁路异步批量落库；DSN 为空或总开关关闭时为 no-op
	// （保证无 DB 环境仍能启动）。defer Close 会排空队列残留后关闭连接池。
	traceWriter := trace.NewWriter(trace.WriterConfig{
		DSN:           cfg.MySQLDSN,
		Enabled:       cfg.TraceEnabled,
		BatchSize:     cfg.TraceBatchSize,
		FlushInterval: cfg.TraceFlushInterval,
		QueueSize:     cfg.TraceQueueSize,
	})
	traceWriter.Start()
	defer traceWriter.Close()

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

	// 编排域 gRPC 客户端（Java 业务域，共用地址；M4-4.7 Studio 工作流编排）。
	workflowClient, err := client.NewWorkflowService(cfg.SessionServiceAddr)
	if err != nil {
		log.Fatalf("连接编排域失败: %v", err)
	}
	defer func() { _ = workflowClient.Close() }()

	// 报表域 gRPC 客户端（Java 业务域，共用地址；M5-5.1 运营报表与满意度）。
	reportClient, err := client.NewReportService(cfg.SessionServiceAddr)
	if err != nil {
		log.Fatalf("连接报表域失败: %v", err)
	}
	defer func() { _ = reportClient.Close() }()

	router := gin.New()
	router.Use(gin.Recovery(), middleware.AccessLog(), middleware.ApiLog(traceWriter, cfg.DefaultTenant))

	// 鉴权与租户上下文（M5-5.5）：可信租户身份由 JWT 解析注入，
	// 之后所有处理器（含 WSS）一律以该身份为唯一租户来源。
	authMiddleware := middleware.Auth(cfg.EnableAuth, cfg.JWTSecret, cfg.DefaultTenant)

	// 长连接：WSS 会话通道（需租户上下文；不套限流，由心跳/缓冲控流）。
	hub := ws.NewHub()
	wsHandler := ws.NewHandler(hub, sessionClient)
	// WSS 按「每条消息收发」记 span（非连接建立处），故在处理器上注入记录器。
	wsHandler.SetRecorder(traceWriter)
	router.GET("/ws", authMiddleware, wsHandler.ServeWS)

	// AI 流式片段消费（M3-3.8）：消费 Kafka chunk 事件并广播至会话连接。
	// 快捷回复建议异步结果消费（M4-4.6）：过载降级后仅推送至该会话的坐席端连接。
	// 停机由 defer cancel 触发消费者正常退出（FetchMessage 返回 ctx 取消）。
	if cfg.KafkaEnabled {
		consumeCtx, cancelConsume := context.WithCancel(context.Background())
		defer cancelConsume()

		consumer := kafka.NewConsumer(cfg.KafkaBrokers, cfg.KafkaChunkTopic, cfg.KafkaGroupID, hub, traceWriter)
		go consumer.Run(consumeCtx)

		suggestConsumer := kafka.NewSuggestConsumer(cfg.KafkaBrokers, cfg.KafkaSuggestTopic, cfg.KafkaGroupID, hub, traceWriter)
		go suggestConsumer.Run(consumeCtx)
	}

	// 探活接口不鉴权（供 LB / K8s 探测）。
	router.GET("/api/v1/health", handler.Health)

	// 管理接口 REST 透传通道（M5-5.6）：登录/用户/系统日志的权威契约在 Java REST 层，
	// 网关仅做协议转换与角色拦截，响应状态码原样回传（业务拒绝与通道故障可区分）。
	proxy := handler.NewProxy(client.NewRestForwarder(cfg.JavaRestAddr))
	// 登录必须豁免鉴权，否则永远无法取得令牌
	router.POST("/api/v1/auth/login", proxy.Forward("/api/v1/auth/login"))

	api := router.Group("/api/v1")
	api.Use(authMiddleware)
	api.Use(middleware.RateLimit(100, 50)) // 默认 100 QPS/IP
	// 租户级限流（M5-5.5）：大租户独立配额，避免单租户挤占全站
	api.Use(middleware.TenantRateLimit(cfg.TenantRateRPS, cfg.TenantRateBurst, cfg.TenantQuotas))

	// 角色拦截（M5-5.6）：admin 为超级角色恒放行；鉴权关闭（本地联调）时跳过
	adminOnly := middleware.RequireRole("admin")
	agentRole := middleware.RequireRole("agent")

	// 认证与用户模块（M5-5.6）：任意已认证用户可查自身身份与登出；用户管理仅 admin
	api.GET("/auth/me", proxy.Forward("/api/v1/auth/me"))
	api.POST("/auth/logout", proxy.Forward("/api/v1/auth/logout"))
	api.GET("/users", adminOnly, proxy.Forward("/api/v1/users"))
	api.POST("/users", adminOnly, proxy.Forward("/api/v1/users"))
	api.POST("/users/:user_id/password", adminOnly, proxy.Forward("/api/v1/users/:user_id/password"))
	api.POST("/users/:user_id/status", adminOnly, proxy.Forward("/api/v1/users/:user_id/status"))
	// 系统日志（M5-5.6）：仅 admin
	api.GET("/logs", adminOnly, proxy.Forward("/api/v1/logs"))
	// 接口链路追踪（M5-5.9）：列表与链路详情，仅 admin；权威契约在 Java trace 模块
	api.GET("/traces", adminOnly, proxy.Forward("/api/v1/traces"))
	api.GET("/traces/:trace_id", adminOnly, proxy.Forward("/api/v1/traces/:trace_id"))

	// 知识库运营（M5-5.6 6.5）：multipart 流式反向代理（仅 admin），
	// 覆盖 /api/v1/kb/bases 与 /api/v1/kb/documents（含分片上传）全部子路径。
	kbProxy, kbErr := handler.NewKBProxy(cfg.JavaRestAddr)
	if kbErr != nil {
		log.Fatalf("知识库代理装配失败: %v", kbErr)
	}
	kbGroup := api.Group("/kb", adminOnly)
	kbGroup.Any("", kbProxy)
	kbGroup.Any("/*rest", kbProxy)

	// 用户端能力：建会话 / 我的历史会话列表（任意已认证角色；身份取自令牌，不接受前端传入）
	api.POST("/session", wsHandler.CreateSession)
	api.GET("/sessions", wsHandler.ListSessions)

	// 客服工作台接口（M2-B）：队列/接管/会话列表/工单（admin/agent）
	agentHandler := handler.NewAgentHandler(sessionClient, ticketClient)
	// 快捷回复建议（M4-4.6）：同步取候选草稿，过载时转异步经坐席端 WSS 推送。
	api.POST("/agent/suggest", agentRole, agentHandler.SuggestReply)
	api.GET("/agent/queue", agentRole, agentHandler.ListQueue)
	api.POST("/agent/takeover", agentRole, agentHandler.TakeOver)
	api.GET("/agent/sessions", agentRole, agentHandler.ListAgentSessions)
	api.POST("/agent/tickets", agentRole, agentHandler.CreateTicket)
	api.GET("/agent/tickets", agentRole, agentHandler.ListTickets)
	api.POST("/agent/tickets/:ticket_id/status", agentRole, agentHandler.UpdateTicketStatus)
	// 坐席回复下行（M2-B）
	api.POST("/agent/reply", agentRole, wsHandler.PushAgentReply)

	// 编排 Studio 接口（M4-4.7）：工作流 CRUD / 草稿 / 发布 / 版本与回滚（仅 admin）
	studioHandler := handler.NewStudioHandler(workflowClient)
	api.GET("/studio/workflows", adminOnly, studioHandler.ListWorkflows)
	api.POST("/studio/workflows", adminOnly, studioHandler.CreateWorkflow)
	api.GET("/studio/workflows/:workflow_id", adminOnly, studioHandler.GetWorkflow)
	api.PUT("/studio/workflows/:workflow_id/draft", adminOnly, studioHandler.SaveDraft)
	api.POST("/studio/workflows/:workflow_id/publish", adminOnly, studioHandler.PublishWorkflow)
	api.POST("/studio/workflows/:workflow_id/rollout", adminOnly, studioHandler.SetRollout)
	api.GET("/studio/workflows/:workflow_id/versions", adminOnly, studioHandler.ListVersions)
	api.GET("/studio/workflows/:workflow_id/versions/:version", adminOnly, studioHandler.GetVersion)
	api.POST("/studio/workflows/:workflow_id/versions/:version/rollback", adminOnly, studioHandler.RollbackVersion)
	api.DELETE("/studio/workflows/:workflow_id", adminOnly, studioHandler.DeleteWorkflow)

	// 运营报表（M5-5.1）：漏斗 / Token 消耗 / CSAT（admin/agent）
	reportHandler := handler.NewReportHandler(reportClient, sessionClient)
	api.GET("/reports/funnel", agentRole, reportHandler.GetFunnel)
	api.GET("/reports/tokens", agentRole, reportHandler.GetTokens)
	api.GET("/reports/csat", agentRole, reportHandler.GetCsat)
	// 满意度评价（M5-5.1）：用户端门户提交（任意已认证角色）
	api.POST("/session/:session_id/feedback", reportHandler.SubmitFeedback)

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
