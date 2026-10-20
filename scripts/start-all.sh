#!/usr/bin/env bash
#
# 灵犀智能平台 — 一键启动脚本（中间件 + 四端服务）
#
# 设计要点：
#   1. **幂等**：已在运行的组件自动跳过（按端口/进程探测），可反复执行；
#   2. **可分段**：--middleware 只起中间件、--services 只起应用、--no-frontend 不起前端；
#   3. **可观测**：stdout/stderr 汇总到 logs/<组件>.log，PID 落 logs/pids/<组件>.pid；
#   4. **诚实报错**：无法启动的组件（如需要 sudo 的 MySQL、未启动的 Docker）给出可执行提示，不假装成功。
#
# 用法：
#   ./scripts/start-all.sh              # 中间件 + 全部服务（默认）
#   ./scripts/start-all.sh --middleware # 仅中间件
#   ./scripts/start-all.sh --services   # 仅应用服务（假设中间件已就绪）
#   ./scripts/start-all.sh --no-frontend
#   ./scripts/start-all.sh status       # 查看各组件状态
#   ./scripts/start-all.sh stop         # 停止应用服务（pid 文件 + 端口兜底）
#   ./scripts/start-all.sh stop --all   # 并停止 Kafka/Milvus/Nacos（不动 MySQL/Redis）
#
# 环境变量（生产部署请覆盖）：
#   LINGXI_JWT_SECRET      网关与 Java 共享的 JWT 密钥（≥32 字节）
#   GATEWAY_DEFAULT_TENANT 默认租户（默认 lingxi）
#   LINGXI_ADMIN_INIT_PASSWORD 内置管理员初始口令（生产必须覆盖）
#   NACOS_HOME / KAFKA_HOME / KAFKA_DATA 中间件安装路径覆盖

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/logs"
PID_DIR="$LOG_DIR/pids"
mkdir -p "$LOG_DIR" "$PID_DIR"

# ---------- 共享配置（禁止硬编码密钥：优先取环境变量，缺省值仅用于本地开发） ----------
export LINGXI_JWT_SECRET="${LINGXI_JWT_SECRET:-lingxi-dev-secret-change-me-please-32bytes}"
export GATEWAY_JWT_SECRET="${GATEWAY_JWT_SECRET:-$LINGXI_JWT_SECRET}"
export GATEWAY_DEFAULT_TENANT="${GATEWAY_DEFAULT_TENANT:-lingxi}"
export LINGXI_DEFAULT_TENANT="${LINGXI_DEFAULT_TENANT:-$GATEWAY_DEFAULT_TENANT}"
export LINGXI_AUTH_ENABLED="${LINGXI_AUTH_ENABLED:-true}"
export GATEWAY_ENABLE_AUTH="${GATEWAY_ENABLE_AUTH:-true}"
export LINGXI_KAFKA_ENABLED="${LINGXI_KAFKA_ENABLED:-true}"
export GATEWAY_KAFKA_ENABLED="${GATEWAY_KAFKA_ENABLED:-true}"

# 链路追踪（M5-5.9）：三端直写 MySQL 的连通参数与总开关（Go 侧用 GATEWAY_MYSQL_DSN，Python 侧用 LINGXI_DB_*）
export LINGXI_TRACE_ENABLED="${LINGXI_TRACE_ENABLED:-true}"
export LINGXI_DB_HOST="${LINGXI_DB_HOST:-127.0.0.1}"
export LINGXI_DB_PORT="${LINGXI_DB_PORT:-3306}"
export LINGXI_DB_USER="${LINGXI_DB_USER:-root}"
export LINGXI_DB_PASSWORD="${LINGXI_DB_PASSWORD:-ideal0921}"
export LINGXI_DB_NAME="${LINGXI_DB_NAME:-lingxi}"
export GATEWAY_MYSQL_DSN="${GATEWAY_MYSQL_DSN:-${LINGXI_DB_USER}:${LINGXI_DB_PASSWORD}@tcp(${LINGXI_DB_HOST}:${LINGXI_DB_PORT})/${LINGXI_DB_NAME}?charset=utf8mb4&parseTime=true&loc=Local}"

# 中间件安装路径（按本机既有环境设定，可用环境变量覆盖）
NACOS_HOME="${NACOS_HOME:-/Users/ideal/Desktop/software/java/nacos}"
REDIS_BIN="${REDIS_BIN:-redis-server}"
MYSQL_SERVER="${MYSQL_SERVER:-/usr/local/mysql/support-files/mysql.server}"

# 端口约定（与各工程配置一致）
PORT_MYSQL=3306
PORT_REDIS=6379
PORT_KAFKA=9092
PORT_MILVUS=19530
PORT_MINIO=9000
PORT_NACOS=8848
PORT_GATEWAY=8080
PORT_JAVA=8081
PORT_GRPC=9090
PORT_AI=8082
PORT_SUGGEST=9095
PORT_WEB=3000

# ---------- 基础工具 ----------

# 端口是否已被监听：IPv4 与 IPv6 各试一次（Vite 等只监听 ::1，仅探 127.0.0.1 会误判为未启动）
port_open() {
  python3 - "$1" <<'PY' 2>/dev/null
import socket, sys
port = int(sys.argv[1])
for family, addr in ((socket.AF_INET, "127.0.0.1"), (socket.AF_INET6, "::1")):
    try:
        s = socket.socket(family); s.settimeout(1)
        if s.connect_ex((addr, port)) == 0:
            sys.exit(0)
        s.close()
    except OSError:
        continue
sys.exit(1)
PY
}

# curl 路径：部分受限 shell 的 PATH 不含 /usr/bin，故显式解析（可用 CURL_BIN 覆盖）
CURL_BIN="${CURL_BIN:-$(command -v curl 2>/dev/null || echo /usr/bin/curl)}"

# HTTP 探活：等待指定 URL 返回期望状态码
wait_http() {
  local url="$1" expect="${2:-200}" timeout="${3:-90}" waited=0
  while [ "$waited" -lt "$timeout" ]; do
    local code
    code="$("$CURL_BIN" -s -m 3 -o /dev/null -W '%{http_code}' "$url" 2>/dev/null || echo 000)"
    [ "$code" = "$expect" ] && return 0
    sleep 3; waited=$((waited + 3))
  done
  return 1
}

# 记录并启动后台进程：$1 组件名，其余为命令（工作目录用 $START_CWD 指定）
run_bg() {
  local name="$1"; shift
  local log="$LOG_DIR/$name.log" pid_file="$PID_DIR/$name.pid"
  ( cd "${START_CWD:-$ROOT_DIR}" && nohup "$@" >>"$log" 2>&1 & echo $! >"$pid_file" )
  echo "  → $name 已拉起（pid=$(cat "$pid_file" 2>/dev/null)，日志 logs/$name.log）"
}

ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
skip() { printf '  \033[33m•\033[0m %s（已在运行，跳过）\n' "$1"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✗\033[0m %s\n' "$1"; }

# ---------- 中间件 ----------

start_middleware() {
  echo "[1/2] 中间件"

  # MySQL：本机为独立安装包（需 root 运行 mysql.server），已在运行则跳过
  if port_open "$PORT_MYSQL"; then
    skip "MySQL:$PORT_MYSQL"
  elif [ -x "$MYSQL_SERVER" ]; then
    warn "MySQL 未运行；尝试启动（若失败请手动执行：sudo $MYSQL_SERVER start）"
    "$MYSQL_SERVER" start >/dev/null 2>&1 || true
    port_open "$PORT_MYSQL" && ok "MySQL:$PORT_MYSQL" || fail "MySQL 启动失败（需 sudo 或改用系统偏好设置启动）"
  else
    fail "未找到 mysql.server（可用 MYSQL_SERVER 环境变量指定路径）"
  fi

  # Redis：本地单机
  if port_open "$PORT_REDIS"; then
    skip "Redis:$PORT_REDIS"
  else
    run_bg "redis" "$REDIS_BIN" --port "$PORT_REDIS" --daemonize no
    port_open "$PORT_REDIS" && ok "Redis:$PORT_REDIS" || fail "Redis 启动失败"
  fi

  # Kafka：仓库内脚本（KRaft 单机，需 JDK21），数据目录持久化
  if port_open "$PORT_KAFKA"; then
    skip "Kafka:$PORT_KAFKA"
  else
    START_CWD="$ROOT_DIR" bash "$ROOT_DIR/scripts/kafka-local.sh" start >/dev/null 2>&1 || true
    port_open "$PORT_KAFKA" && ok "Kafka:$PORT_KAFKA" || fail "Kafka 启动失败（检查 scripts/kafka-local.sh 与 JDK21）"
  fi

  # Milvus 栈：docker 容器（含 MinIO，9000 端口供知识库对象存储复用）
  if port_open "$PORT_MILVUS"; then
    skip "Milvus:$PORT_MILVUS"
    skip "MinIO:$PORT_MINIO"
  elif docker info >/dev/null 2>&1; then
    docker start milvus-etcd milvus-minio milvus-standalone >/dev/null 2>&1 || true
    sleep 5
    port_open "$PORT_MILVUS" && ok "Milvus:$PORT_MILVUS" || fail "Milvus 启动失败（检查 docker 容器 milvus-*）"
    port_open "$PORT_MINIO" && ok "MinIO:$PORT_MINIO" || warn "MinIO:$PORT_MINIO 未就绪"
  else
    fail "Docker 未运行，无法启动 Milvus/MinIO（请先启动 Docker Desktop）"
  fi

  # Nacos：standalone 模式（注册中心，Java 侧注册用）
  if port_open "$PORT_NACOS"; then
    skip "Nacos:$PORT_NACOS"
  elif [ -x "$NACOS_HOME/bin/startup.sh" ]; then
    JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
      sh "$NACOS_HOME/bin/startup.sh" -m standalone >/dev/null 2>&1 || true
    wait_http "http://127.0.0.1:$PORT_NACOS/nacos" 200 90 >/dev/null 2>&1 \
      && ok "Nacos:$PORT_NACOS" || fail "Nacos 启动失败（检查 ${NACOS_HOME}）"
  else
    fail "未找到 Nacos（可用 NACOS_HOME 环境变量指定路径）"
  fi
}

# ---------- 应用服务 ----------

start_services() {
  echo "[2/2] 应用服务"

  # Java 业务域：优先运行已打包的可执行 jar（避免依赖本地 Maven 仓库写入），缺失则先打包
  if port_open "$PORT_JAVA"; then
    skip "core-java:$PORT_JAVA"
  else
    local jar
    jar="$(ls "$ROOT_DIR"/core-java/start/target/lingxi-start-*-SNAPSHOT.jar 2>/dev/null | head -1 || true)"
    if [ -z "$jar" ]; then
      warn "未找到可执行 jar，先执行打包：mvn -B -q -DskipTests package"
      ( cd "$ROOT_DIR/core-java" && mvn -B -q -DskipTests package ) || fail "Java 打包失败"
      jar="$(ls "$ROOT_DIR"/core-java/start/target/lingxi-start-*-SNAPSHOT.jar 2>/dev/null | head -1 || true)"
    fi
    if [ -n "$jar" ]; then
      START_CWD="$ROOT_DIR/core-java" run_bg "core-java" java -jar "$jar"
      wait_http "http://127.0.0.1:$PORT_JAVA/actuator/health" 200 240 >/dev/null 2>&1 \
        && ok "core-java:$PORT_JAVA" || fail "core-java 启动失败（见 logs/core-java.log）"
    else
      fail "Java 可执行 jar 不存在，跳过"
    fi
  fi

  # Go 网关：先编译为二进制再运行。
  # 为什么不用 `go run`：首次编译（尤其依赖升级后重建）可能耗时长于健康等待超时，
  # 会出现「进程已起但脚本判定失败」的假阴性；预编译后启动仅需百毫秒。
  if port_open "$PORT_GATEWAY"; then
    skip "gateway-go:$PORT_GATEWAY"
  else
    local gw_bin="$ROOT_DIR/gateway-go/bin/gateway"
    if [ ! -x "$gw_bin" ] || [ -n "$(find "$ROOT_DIR/gateway-go/cmd" "$ROOT_DIR/gateway-go/internal" \
        -newer "$gw_bin" 2>/dev/null | head -1)" ]; then
      warn "编译网关二进制（依赖或源码有变化）..."
      ( cd "$ROOT_DIR/gateway-go" && go build -o bin/gateway ./cmd/gateway ) \
        || { fail "网关编译失败（请人工检查 go build 输出）"; }
    fi
    if [ -x "$gw_bin" ]; then
      START_CWD="$ROOT_DIR/gateway-go" run_bg "gateway-go" ./bin/gateway
      wait_http "http://127.0.0.1:$PORT_GATEWAY/api/v1/health" 200 120 >/dev/null 2>&1 \
        && ok "gateway-go:$PORT_GATEWAY" || fail "gateway-go 启动失败（见 logs/gateway-go.log）"
    else
      fail "网关二进制不存在，跳过"
    fi
  fi

  # Python AI 域：uvicorn（含快捷回复 gRPC 9095；Kafka 开关由 ai-python/.env 决定）
  if port_open "$PORT_AI"; then
    skip "ai-python:$PORT_AI"
  else
    START_CWD="$ROOT_DIR/ai-python" run_bg "ai-python" ./.venv/bin/uvicorn app.main:app --port "$PORT_AI"
    wait_http "http://127.0.0.1:$PORT_AI/health" 200 120 >/dev/null 2>&1 \
      && ok "ai-python:$PORT_AI" || fail "ai-python 启动失败（见 logs/ai-python.log）"
  fi

  # 前端：Vite dev server（/api 与 /ws 代理到网关）
  if [ "${NO_FRONTEND:-0}" = "1" ]; then
    warn "按参数跳过前端"
    return 0
  fi
  if port_open "$PORT_WEB"; then
    skip "web-vue3:$PORT_WEB"
  else
    START_CWD="$ROOT_DIR/web-vue3" run_bg "web-vue3" npm run dev
    # 用 localhost（Vite 默认绑定 ::1），避免 IPv4-only 探测误判
    wait_http "http://localhost:$PORT_WEB/" 200 120 >/dev/null 2>&1 \
      && ok "web-vue3:$PORT_WEB" || fail "web-vue3 启动失败（见 logs/web-vue3.log）"
  fi
}

# ---------- 状态 / 停止 ----------

show_status() {
  echo "组件状态（端口探测 + docker 容器）"
  local pairs=(
    "MySQL:$PORT_MYSQL" "Redis:$PORT_REDIS" "Kafka:$PORT_KAFKA" "Milvus:$PORT_MILVUS"
    "MinIO:$PORT_MINIO" "Nacos:$PORT_NACOS" "gateway-go:$PORT_GATEWAY" "core-java:$PORT_JAVA"
    "ai-python:$PORT_AI" "suggest-grpc:$PORT_SUGGEST" "web-vue3:$PORT_WEB"
  )
  for pair in "${pairs[@]}"; do
    local name="${pair%%:*}" port="${pair##*:}"
    if port_open "$port"; then ok "$name  ($port)"; else fail "$name  ($port)"; fi
  done
  echo "Docker 容器："
  docker ps --format '  {{.Names}}  {{.Status}}' 2>/dev/null | grep -E "milvus" || warn "无 milvus 容器"
}

stop_services() {
  echo "停止应用服务"
  for name in web-vue3 ai-python gateway-go core-java; do
    local pid_file="$PID_DIR/$name.pid"
    if [ -f "$pid_file" ]; then
      local pid; pid="$(cat "$pid_file")"
      # 变量后若紧跟中文全角括号必须写成 ${var}，否则 bash 会把多字节字符并入变量名导致 unbound variable
      kill "$pid" 2>/dev/null && ok "$name 已停止（pid=${pid}）" || warn "$name pid=${pid} 已不存在"
      rm -f "$pid_file"
    fi
  done
  # 端口兜底：清理由其它方式（IDE/手动）拉起的残留进程
  for port in "$PORT_WEB" "$PORT_AI" "$PORT_GATEWAY"; do
    local pids; pids="$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null || true)"
    [ -n "$pids" ] && kill $pids 2>/dev/null && ok "释放端口 $port"
  done
  # Java 由 java -jar 启动，按命令行特征兜底
  pkill -f "lingxi-start-.*\.jar" 2>/dev/null && ok "core-java 已停止" || true

  if [ "${STOP_ALL:-0}" = "1" ]; then
    echo "停止中间件（不动 MySQL/Redis：系统级服务，请按需自行管理）"
    START_CWD="$ROOT_DIR" bash "$ROOT_DIR/scripts/kafka-local.sh" stop >/dev/null 2>&1 && ok "Kafka 已停止" || warn "Kafka 停止跳过"
    docker stop milvus-standalone milvus-minio milvus-etcd >/dev/null 2>&1 && ok "Milvus 栈已停止" || warn "Milvus 停止跳过"
    sh "$NACOS_HOME/bin/shutdown.sh" >/dev/null 2>&1 && ok "Nacos 已停止" || warn "Nacos 停止跳过"
  fi
}

usage() {
  sed -n '3,28p' "$0" | sed 's/^# \{0,1\}//'
}

# ---------- 入口 ----------

MODE="all"
case "${1:-}" in
  stop)       MODE="stop" ;;
  status)     MODE="status" ;;
  --middleware) MODE="middleware" ;;
  --services)   MODE="services" ;;
  -h|--help)  usage; exit 0 ;;
  "")         MODE="all" ;;
  *)          fail "未知参数：$1"; usage; exit 1 ;;
esac
[ "${2:-}" = "--all" ] && STOP_ALL=1
[ "${2:-}" = "--no-frontend" ] && NO_FRONTEND=1

case "$MODE" in
  status) show_status ;;
  stop)   stop_services ;;
  middleware) start_middleware; echo; show_status ;;
  services)   start_services; echo; show_status ;;
  all) start_middleware; echo; start_services; echo; show_status; echo; echo "访问：http://localhost:$PORT_WEB （管理员 admin / 见 LINGXI_ADMIN_INIT_PASSWORD）" ;;
esac
