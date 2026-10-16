# deploy/offline — 私有化离线交付包（M5-5.4）

内网/离线机器上「一键完成依赖检查 → 安装 → 启动 → 健康校验 → 生成交付报告」的交付物集。
全部配置与脚本为中文注释；**所有能力均标注是否在本机验证过**（见文末对照表）。

## 目录结构

```
deploy/offline/
├── README.md                    本文件
├── manifest.yaml                版本与资产清单（唯一事实来源，打包/安装脚本均解析它）
├── install-offline.sh           离线机器一键安装/部署（幂等，支持 --dry-run）
├── docker-compose.offline.yml   Milvus 栈离线编排（只用本地镜像名，禁 docker pull）
├── config/
│   ├── .env.example             必需环境变量样例（含 [必改] 标注）
│   └── nginx.conf.example       前端 dist 静态托管 + /api、/ws 反代网关
├── lib/common.sh                打包与安装脚本共享工具（探活、manifest 解析、日志）
├── reports/                     安装交付报告输出目录
└── dist/                        构建产物：lingxi-offline-<版本>-<时间戳>/ 与 .tar.gz
```

## 离线部署步骤

1. **联网源机打包**：`bash scripts/build-offline-bundle.sh` → 产出 `dist/lingxi-offline-<版本>-<时间戳>.tar.gz` 与同名 `.sha256`。
2. **介质拷贝**：将 tar 包（及离线 docker 镜像 tar）拷入内网目标机。
3. **目标机安装**：`bash install-offline.sh --bundle lingxi-offline-*.tar.gz`（先校验、再解包、再启动）。
4. **填配置**：`cp <安装目录>/config/.env.example <安装目录>/config/.env` 并填写 `[必改]` 项。
5. **验收入口**：浏览器打开 `http://<目标机IP>:3000`，管理员见 `.env` 的 `LINGXI_ADMIN_INIT_PASSWORD`。
6. **查看报告**：`<包>/deploy/offline/reports/install-<时间戳>.md`。

## 前置依赖（目标机需预先就绪，**本包不自带中间件安装介质**）

| 类别 | 要求 |
|---|---|
| 硬件 | ≥4 核 / ≥16GB 内存 / ≥50GB 磁盘（Milvus 栈 + 四端应用） |
| 操作系统 | Linux x86_64 或 macOS；需可执行 `bash` 脚本 |
| 软件 | JDK 21（core-java）、Python 3.11+（ai-python）、curl、tar；docker（Milvus 栈可选）、node（仅前端 dev 可选） |
| 中间件 | MySQL 8.x、Redis 5+；可选 Kafka（KRaft）、Milvus 2.6+（含 MinIO）、Nacos |
| 离线镜像 | Milvus 栈须预先 `docker load`（镜像名见 `docker-compose.offline.yml` 头部注释） |

## 端口清单

| 端口 | 组件 | 端口 | 组件 |
|---|---|---|---|
| 3306 | MySQL | 8080 | gateway-go（网关） |
| 6379 | Redis | 8081 | core-java（业务域） |
| 9092 | Kafka | 8082 | ai-python（AI 域） |
| 19530 / 9091 | Milvus / 其指标 | 9095 | ai-python 快捷回复 gRPC |
| 9000 / 9001 | MinIO / 控制台 | 3000 | web-vue3（前端） |
| 8848 | Nacos | | |

## 配置项与外置环境变量

- 全部必需环境变量见 `config/.env.example`：JWT 密钥（`LINGXI_JWT_SECRET`）、管理员初始口令
  （`LINGXI_ADMIN_INIT_PASSWORD`）、默认租户（`GATEWAY_DEFAULT_TENANT`）、MySQL（`LINGXI_DB_*`）、
  Redis（`LINGXI_REDIS_*`）、Kafka（`LINGXI_KAFKA_*`）、Milvus（`LINGXI_MILVUS_URI`）、
  MinIO（`LINGXI_KB_MINIO_*`）、LLM/Embedding/Rerank 各 Provider 的 Key 与 Base URL。
- 覆盖优先级：**系统环境变量 > `config/.env` > 启动脚本内默认值**；`scripts/start-all.sh` 亦支持
  `NACOS_HOME / KAFKA_HOME / KAFKA_DATA / MYSQL_SERVER` 等路径覆盖。

## 卸载 / 回滚

1. 停服务：`cd <安装目录> && bash scripts/start-all.sh stop --all`（不动 MySQL/Redis，二者为系统级服务）。
2. 停止 Milvus 栈：`docker compose -f docker-compose.offline.yml down`（数据卷保留，加 `-v` 才清数据）。
3. 移除应用：删除 `<安装目录>`（默认 `/opt/lingxi`）。
4. 数据库回滚：恢复 MySQL 备份；Milvus/MinIO 数据在 docker 卷中，如需清理再删卷。

## 已验证 / 未验证能力对照表

| 能力 | 状态 | 说明 |
|---|---|---|
| 交付包构建（收集四端产物、生成 SHA256SUMS/bundle-info） | ✅ 已验证 | 本机实跑通过（见汇报） |
| 打包脚本缺失项显式报告 | ✅ 已验证 | 逐项打印「已包含/缺失」并汇总 |
| 安装脚本前置检查与 `--dry-run` | ✅ 已验证 | 本机对仓库实跑，输出报告 |
| 中间件就绪探测（MySQL/Redis/Kafka/Milvus/MinIO/Nacos） | ✅ 已验证 | 只读探测本机在跑实例 |
| 四端健康 URL 正确性 | ✅ 已验证 | 网关 `/api/v1/health`、Java `/actuator/health`、AI `/health`、前端 `localhost:3000` |
| 真实离线机器端到端安装与启动 | ⚠️ 未验证 | 无独立离线机环境，仅在本机自检 |
| Milvus 栈 compose 在干净环境拉起 | ⚠️ 未验证 | 本机现有容器为历史手工创建，compose 文件未实测 |
| nginx 托管 dist 并反代 | ⚠️ 未验证 | 本机未用 nginx 实际加载该配置 |
| Python wheels 离线缓存（pip download） | ⚠️ 未验证 | 本次构建机可联网、已下载 110 个 wheel；但 wheel 为**构建机平台专属**（cpXX-平台），目标机需同平台或改用源码/pyproject 安装 |
| K8s / Helm 形态部署 | ⚠️ 未验证 | `deploy/` 下 Helm 仍为占位 |
