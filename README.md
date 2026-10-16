# lingxi_agent — 大型 AI Agent 智能客户应用

Monorepo 工程，Go 网关 + Java 业务 + Python AI + Vue3 前端。

详细设计见 [docs](./docs/)：
- [架构设计](./docs/架构设计.md)（含知识库存储与索引设计）
- [工程设计与开发规划](./docs/工程设计与开发规划.md)
- [已完成功能点](./docs/已完成功能点.md)（v4.5，已交付功能点与文件级说明）

## 关键结果（2026-10-16 里程碑现状）

> 「已实测」= 有真实运行证据；「未验证」= 环境或权限不具备，**不假装达标**。

### 已实测验证

| 模块 | 关键结果 |
|---|---|
| 登录与权限 | 账号密码登录（BCrypt + HS256 JWT，网关与 Java 同密钥）；RBAC `admin/agent/user` 双层校验（网关路由级 + Java `@RequireRole`）；**租户隔离**可信上下文（伪造 `tenant_id` → 403）；操作审计只追加表 + 日志页 |
| 知识库 | 建库 → 分类 → 单请求/分片上传 → 入库回写 `INDEXED` → 下线；知识库 REST 仅 admin，租户取自身份而非前端入参 |
| AI 问答链路 | 流式问答 `Kafka → AI → chunk → Go → WSS`；**消费并发优化后**：并发 6 成功率 100%，**TTFT P50 4452.6 → 1174.9 ms（-74%）**、TTFT P95 6872.7 → 2181.8 ms、整轮 P50 5070.6 → 1544.8 ms |
| 安全加固 | Prompt 注入三层防护（离线 19 项单测）；Golden Set 评测 **32/32**；接口渗透 **25/25**；依赖扫描已处置 `golang.org/x/net` → v0.60.0、`pip` → 26.2.1 |
| 全链路压测 | `scripts/bench/load-test.py`（单机基线，非分布式）：REST（8 并发/20s）**有效成功率 100%**、QPS 97.6、P50 29.6 ms / P95 43.9 ms；chat（6 并发）成功率 100%。报告见 `scripts/bench/reports/` |
| 私有化离线交付 | `deploy/offline/` + `scripts/build-offline-bundle.sh`：产物 **215 MB**，`SHA256SUMS` **293 项全 OK**、缺失清单 0、构建幂等；安装 **dry-run 通过** |
| 登录页科技感动画 | 外太空 + 飞船场景（星野/行星/空间站/流星/陨石带/跃迁光条/星座）；电影感分段缓动；指针视差；登录状态联动（充能/爆发/告警）；**浏览器实测元素可见且在动、窄屏无溢出**；低端设备 `.is-lite` 精简模式 |
| 会话窗口 | 智能会话支持「新建会话」（申请新 `sessionId` + 清空窗口与评价态，旧会话在服务端保留）；**新增历史会话列表（左列 248px + 点击切换，切换后回源拉该会话历史）——会话与消息已真正落 MySQL（`lingxi_session`/`lingxi_session_message`），列表走 `GET /api/v1/sessions`（租户/用户取自令牌，按本人主体返回、按最近活跃倒序）**；会话区铺满内容区剩余高度；运营报表已移除「返回用户端」；**新增会话并发写丢数据修复**——消息 seq 改由存储侧 `appendMessage` 分配（MySQL 以 `SELECT message_count ... FOR UPDATE` 仅锁会话行，同会话串行、跨会话互不阻塞），**真实 MySQL 实测同会话 8 并发 8/8 全部落库、seq 恰为 1..8**。**边界（如实标注）**：本次为**单实例 + 真实 MySQL**验证，**多实例（多 Java 进程）并发未验证** |

### 关键配置（易踩坑项）

| 配置 | 说明 |
|---|---|
| `LINGXI_KAFKA_ENABLED=true` | **完整流式问答必需**。置 `false` 时 AI 侧消费协程不启动 → 用户消息进 `lingxi.session.message` 无人消费 → 前端 60s 收不到任何片段 |
| `LINGXI_KAFKA_SESSION_MESSAGE_WORKERS=4` | 会话消息消费并发度（分区级并行：分区内有序＝会话内有序）；实际并发 = `min(本值, 分区数)` |
| 分区数 12 | 会话消息主题由 `./scripts/kafka-local.sh init-topics` 建 **12** 分区（幂等 alter；分区数提升「多会话落同一分区」的分散度） |
| 网关限流 | 单 IP / 单租户各 **100 RPS**（burst 50）。单机压测出现 `HTTP 429` 属**限流预期生效**，非故障 |
| `LINGXI_SESSION_STORE` | 会话存储实现开关（`lingxi.session.store`，**默认 `mysql`**）。置 `inmemory` 才回退内存仓储——**仅单测/无 MySQL 环境用**；默认 `mysql` 时会话与消息落库到 `lingxi_session`/`lingxi_session_message`，历史会话列表（`GET /api/v1/sessions`）才能持久可查 |

### 未验证（如实标注）

- 压测的**分布式**指标（QPS 5000+ / 50 万长连接）——需多机 / K8s 集群与压测集群隔离；
- 多云双活 Region 切换演练（RTO < 5min / 数据零丢失）——需真实多 Region 环境；
- 私有化包在**干净离线机**的端到端安装启动、Helm/K8s 部署、nginx 托管反代；
- 依赖层：Go 标准库 toolchain（go1.27.1 → ≥1.27.2）、Node/Java 自动扫描能力缺口（尚未清零）；
- 登录页 390px 真机渲染（macOS 无头 Chrome 有最小窗口宽度，以 493px 浏览器实测 + 520px 无头真渲染覆盖该断点）。

## 目录结构

| 目录 | 说明 |
|---|---|
| `gateway-go/` | Go 接入网关（BFF/WSS 长连接） |
| `core-java/` | Java 业务域（Spring Boot 多模块） |
| `ai-python/` | Python AI 域（Agent/RAG/LLM 网关） |
| `web-vue3/` | Vue3 前端（用户端/工作台/管理台/Studio） |
| `contracts/proto/` | 跨语言 gRPC 契约 |
| `deploy/` | 交付说明 + 离线交付包与安装器（`deploy/offline/`） |
| `scripts/` | 一键启动、Kafka/Milvus 助手、压测、安全扫描等工程脚本 |
| `docs/` | 架构设计 / 工程设计与开发规划 / 已完成功能点 |
| `logs/` | 运行期日志与 PID（gitignore，非源码） |

## 快速开始

### 一键启动（推荐）

```bash
# 启动中间件（MySQL/Redis/Kafka/Milvus/Nacos）+ 四端服务（Go/Java/Python/Vue）
make up                      # 等价于 ./scripts/start-all.sh（幂等，可重复执行）

make status                  # 查看各组件状态（端口探测 + docker 容器）
make down                    # 停止应用服务（保留中间件与数据）
make down-all                # 停止应用服务 + Kafka/Milvus/Nacos（不动 MySQL/Redis）
```

- 启动日志统一写入仓库根目录 `logs/<组件>.log`，PID 落 `logs/pids/<组件>.pid`（已 gitignore）。
- 常用参数：`./scripts/start-all.sh --middleware`（仅中间件）、`--services`（仅应用）、`--no-frontend`。
  参数**必须带 `--`**（写成 `start-all.sh services` 会报「未知参数」退出）。
- 判定「是否就绪」**以 `make status` 为准**：脚本等待 `/health` 有超时窗口，启动偏慢时会打印
  「启动失败」但实际已就绪；脚本按**端口占用**判幂等，故可反复执行。
- 若在会回收子进程的环境执行启动器（部分 IDE 任务 / 自动化 runner），请让它保持后台运行：
  `nohup ./scripts/start-all.sh --services >> logs/start-all.out 2>&1 &`
- MySQL 由本机独立安装包提供（`mysql.server start` 可能需要 `sudo`），Milvus/MinIO 依赖
  **Docker Desktop 已启动**；`stop` 不动 MySQL/Redis（系统级服务，请按需自行管理）。
- 共享配置可用环境变量覆盖（生产必须覆盖）：`LINGXI_JWT_SECRET`（网关与 Java 同值，≥32 字节）、
  `LINGXI_ADMIN_INIT_PASSWORD`、`GATEWAY_DEFAULT_TENANT`；中间件路径 `NACOS_HOME` / `KAFKA_HOME`。

启动完成后访问：前端 http://localhost:3000 （管理员账号 `admin`，初始口令由 `LINGXI_ADMIN_INIT_PASSWORD` 决定）

### 手动启动（逐服务，便于调试）

```bash
# 1. 中间件（若未使用一键脚本）
make dev                     # 容器版中间件（docker-compose.dev.yml）
./scripts/kafka-local.sh start   # 本地 KRaft 单机 Kafka（tarball 方案）

# 2. 初始化各工程依赖
make init

# 3. 分别启动四服务（各开一个终端）
cd gateway-go && go run ./cmd/gateway                 # :8080
cd core-java  && mvn -q -pl start spring-boot:run      # :8081 (/actuator/health)
cd ai-python  && make dev                              # :8082 (/health)
cd web-vue3   && npm run dev                           # :3000

# 4. 健康检查
curl localhost:8080/api/v1/health
curl localhost:8081/actuator/health
curl localhost:8082/health
```

## 常用命令

| 命令 | 说明 |
|---|---|
| `make up` / `make status` / `make down` / `make down-all` | 一键启动 / 查看状态 / 停应用服务 / 连中间件一起停（详见「快速开始」） |
| `make dev` | 启动容器版本地中间件（`docker-compose.dev.yml`） |
| `make init` | 初始化各工程依赖（go mod / pip / npm） |
| `make build` / `make test` / `make lint` | 各工程构建 / 全量单测 / 全量静态检查 |
| `make sec-scan` | 依赖漏洞扫描（四语言，报告落 `scripts/security/reports/`） |
| `make pentest` | 接口渗透用例（需服务已启动，报告落 `scripts/security/reports/`） |

### 基线压测（单机，非分布式）

```bash
# 需服务已启动；默认账号 admin / lingxi@2026 / 租户 lingxi（可用 BENCH_* 环境变量覆盖）
./ai-python/.venv/bin/python scripts/bench/load-test.py \
    --scenario both --users 8 --chat-users 6 --duration 20
# 报告写入 scripts/bench/reports/bench-<时间戳>.md
```

### 私有化离线交付包

```bash
bash scripts/build-offline-bundle.sh   # 产物落 deploy/offline/dist/（含 SHA256SUMS 与 .sha256）
bash deploy/offline/install-offline.sh --dry-run   # 目标机安装自检（只读，不启停服务）
```
