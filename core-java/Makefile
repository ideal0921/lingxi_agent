.PHONY: build run test lint proto

# 项目默认 JDK21（目标版本）；可用命令行覆盖：make test JAVA_HOME=/path/to/jdk
JAVA_HOME := /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
MVN := JAVA_HOME=$(JAVA_HOME) PATH=$(JAVA_HOME)/bin:$$PATH mvn

build: ## 打包可执行 jar（含契约模块）
	$(MVN) -q -B package -DskipTests

run: ## 本地运行
	$(MVN) -q -B -pl start -am spring-boot:run

test: ## 单元测试
	$(MVN) -q -B test

lint: ## 静态检查（Checkstyle）
	$(MVN) -q -B checkstyle:check

proto: ## 生成 gRPC 契约代码（Maven protobuf 插件）
	$(MVN) -q -B -pl gateway-api generate-sources
