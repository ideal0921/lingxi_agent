#!/usr/bin/env bash
# 生成跨语言 gRPC 契约代码。
# 依赖：ai-python/.venv 中 grpcio-tools（自带完整 protoc 二进制，避免本机安装 protoc）；
#       Go 插件 protoc-gen-go / protoc-gen-go-grpc（go install 安装）；
#       Java 侧改用 Maven protobuf-maven-plugin 生成（见 core-java/gateway-api/pom.xml）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROTO_DIR="$ROOT/contracts/proto"
PROTOC="$ROOT/ai-python/.venv/bin/python -m grpc_tools.protoc"
export PATH="$(go env GOPATH)/bin:$PATH"   # 供 protoc-gen-go / protoc-gen-go-grpc 使用

echo "==> 生成 Go 契约（gateway-go/proto）"
mkdir -p "$ROOT/gateway-go/proto"
$PROTOC -I "$PROTO_DIR" \
  --go_out="$ROOT/gateway-go/proto" --go_opt=paths=source_relative \
  --go-grpc_out="$ROOT/gateway-go/proto" --go-grpc_opt=paths=source_relative \
  "$PROTO_DIR"/*.proto

echo "==> Java 契约由 Maven 生成：cd core-java && mvn -q -B -pl gateway-api generate-sources"

echo "==> 生成 Python 契约（ai-python/proto）"
mkdir -p "$ROOT/ai-python/proto"
$PROTOC -I "$PROTO_DIR" \
  --python_out="$ROOT/ai-python/proto" \
  --grpc_python_out="$ROOT/ai-python/proto" \
  "$PROTO_DIR"/*.proto

echo "契约生成完成"
