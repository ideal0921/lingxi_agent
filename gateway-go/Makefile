.PHONY: build run test lint tidy

build: ## 编译二进制
	go build -o bin/gateway ./cmd/gateway

run: ## 本地运行
	go run ./cmd/gateway

test: ## 单元测试
	go test ./...

lint: ## 静态检查
	golangci-lint run ./...

tidy: ## 整理依赖
	go mod tidy
