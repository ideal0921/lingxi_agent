# lingxi_agent — 大型 AI Agent 智能客户应用

Monorepo 工程，Go 网关 + Java 业务 + Python AI + Vue3 前端。

详细设计见 [docs](./docs/)：
- [架构设计](./docs/架构设计.md)（含知识库存储与索引设计）
- [工程设计与开发规划](./docs/工程设计与开发规划.md)

## 目录结构

| 目录 | 说明 |
|---|---|
| `gateway-go/` | Go 接入网关（BFF/WSS 长连接） |
| `core-java/` | Java 业务域（Spring Boot 多模块） |
| `ai-python/` | Python AI 域（Agent/RAG/LLM 网关） |
| `web-vue3/` | Vue3 前端（用户端/工作台/管理台/Studio） |
| `deploy/` | Helm Charts / 离线交付 |
| `ops/` | 可观测性配置 |
| `contracts/proto/` | 跨语言 gRPC 契约 |

## 快速开始（M1 骨架）

```bash
# 1. 拉起本地中间件（MySQL/Redis/ES/Kafka）
make dev

# 2. 初始化各工程依赖
make init

# 3. 分别启动四服务（各开一个终端）
cd gateway-go && go run ./cmd/gateway        # :8080
cd core-java  && mvn -q -pl start spring-boot:run  # :8081
cd ai-python  && make dev                    # :8082
cd web-vue3   && npm run dev                 # :3000

# 4. 健康检查
curl localhost:8080/api/v1/health
curl localhost:8081/actuator/health
curl localhost:8082/health
```

## 常用命令

| 命令 | 说明 |
|---|---|
| `make dev` / `make down` | 启动/停止本地中间件 |
| `make test` | 全量单测 |
| `make lint` | 全量静态检查 |
