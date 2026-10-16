# 顶层命令入口（lingxi_agent）
.PHONY: up status down down-all dev build test lint init sec-scan pentest

init: ## 初始化各工程依赖
	cd gateway-go && go mod tidy
	cd ai-python && make init
	cd web-vue3 && npm install

up: ## 一键启动：中间件 + 四端服务（幂等；日志见 logs/）
	./scripts/start-all.sh

status: ## 查看各组件运行状态
	./scripts/start-all.sh status

down: ## 停止应用服务（保留中间件与数据）
	./scripts/start-all.sh stop

down-all: ## 停止应用服务 + Kafka/Milvus/Nacos（不动 MySQL/Redis）
	./scripts/start-all.sh stop --all

dev: ## 拉起本地开发中间件（容器版，docker-compose.dev.yml）
	docker compose -f docker-compose.dev.yml up -d

build: ## 构建各工程
	cd gateway-go && make build
	cd ai-python && make build
	cd web-vue3 && make build

test: ## 各工程单测
	cd gateway-go && make test
	cd ai-python && make test
	cd core-java && make test
	cd web-vue3 && npm run typecheck

lint: ## 各工程静态检查
	cd gateway-go && make lint
	cd ai-python && make lint
	cd web-vue3 && make lint

sec-scan: ## 依赖漏洞扫描（四语言，报告写入 scripts/security/reports/）
	./scripts/security/dependency-scan.sh

pentest: ## 接口渗透用例（需服务已启动，报告写入 scripts/security/reports/）
	./scripts/security/pentest.py
