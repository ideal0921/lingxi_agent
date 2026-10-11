# 顶层命令入口（lingxi_agent）
.PHONY: dev down build test lint init

init: ## 初始化各工程依赖
	cd gateway-go && go mod tidy
	cd ai-python && make init
	cd web-vue3 && npm install

dev: ## 拉起本地开发中间件（MySQL/Redis/ES/Kafka）
	docker compose -f docker-compose.dev.yml up -d

down: ## 停止本地开发中间件
	docker compose -f docker-compose.dev.yml down

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
