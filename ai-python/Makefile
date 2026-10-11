.PHONY: init dev test lint build

PY := .venv/bin/python
PIP := .venv/bin/pip

init: ## 安装依赖
	$(PIP) install -e ".[dev]"

dev: ## 本地热更新启动
	.venv/bin/uvicorn app.main:app --reload --port 8082

test: ## 单元测试
	.venv/bin/pytest -q

lint: ## 静态检查
	.venv/bin/ruff check app tests

build: ## 打包 wheel
	$(PIP) wheel . -w dist
