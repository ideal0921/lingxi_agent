#!/usr/bin/env bash
# 本地 Kafka 启停助手（M3-3.8 流式全链路）
#
# 背景：本机 Docker Hub 与国内镜像源均不可达，无法用 docker-compose 拉起 Kafka，
# 改为「Apache 镜像下载发行版 + KRaft 单机」方式本地运行。目录与数据均持久化。
#
# 目录约定（可用环境变量覆盖）：
#   KAFKA_HOME  发行版目录，默认 $HOME/lingxi-kafka（由 kafka_2.13-<ver>.tgz 解压）
#   KAFKA_DATA  数据/日志目录，默认 $HOME/lingxi-kafka-data（主题与消息持久保存）
#   JAVA_HOME   Kafka 4.x 要求 Java 17+，默认指向 temurin-21
#
# 用法：scripts/kafka-local.sh {start|stop|restart|status|init-topics|logs}
#
# 服务侧开关（三端需一致开启才会走 Kafka 链路）：
#   core-java : LINGXI_KAFKA_ENABLED=true（application.yml 的 lingxi.kafka.enabled）
#   ai-python : LINGXI_KAFKA_ENABLED=true
#   gateway-go: GATEWAY_KAFKA_ENABLED=true
set -euo pipefail

KAFKA_HOME="${KAFKA_HOME:-$HOME/lingxi-kafka}"
KAFKA_DATA="${KAFKA_DATA:-$HOME/lingxi-kafka-data}"
JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}"
export JAVA_HOME

BOOTSTRAP="localhost:9092"
# 流式全链路（M3-3.8）：会话消息 → AI 片段 → 最终回答；会话摘要回写（M3-3.11）
# 知识库链路（M3-3.2）：入库任务 → 入库结果回写 → 下线清理
TOPICS=("lingxi.session.message" "lingxi.ai.chunk" "lingxi.ai.reply" "lingxi.session.summary"
        "kb.doc.ingest" "kb.doc.indexed" "kb.doc.delete")
PID_FILE="$KAFKA_DATA/kafka.pid"
LOG_FILE="$KAFKA_DATA/kafka.log"

die() { echo "错误：$*" >&2; exit 1; }

require_home() {
  [ -x "$KAFKA_HOME/bin/kafka-server-start.sh" ] || die "未找到 Kafka 发行版：${KAFKA_HOME}（请先解压发行版或设置 KAFKA_HOME）"
}

is_running() {
  [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null
}

wait_port() {
  local tries="${1:-30}"
  for _ in $(seq 1 "$tries"); do
    if nc -z 127.0.0.1 9092 2>/dev/null; then return 0; fi
    sleep 1
  done
  return 1
}

do_start() {
  require_home
  if is_running; then echo "Kafka 已在运行（pid $(cat "$PID_FILE")）"; return 0; fi
  if nc -z 127.0.0.1 9092 2>/dev/null; then
    echo "注意：9092 已被占用（可能是手工启动的 Kafka），跳过启动"; return 0
  fi
  mkdir -p "$KAFKA_DATA"
  echo "启动 Kafka（${KAFKA_HOME}）..."
  nohup "$KAFKA_HOME/bin/kafka-server-start.sh" "$KAFKA_HOME/config/server.properties" \
    >"$LOG_FILE" 2>&1 &
  echo $! >"$PID_FILE"
  if wait_port 60; then
    echo "Kafka 已就绪：${BOOTSTRAP}（日志：${LOG_FILE}）"
  else
    die "Kafka 启动超时，请查看日志：${LOG_FILE}"
  fi
}

do_stop() {
  if ! is_running; then echo "Kafka 未在运行"; return 0; fi
  local pid; pid="$(cat "$PID_FILE")"
  echo "停止 Kafka（pid ${pid}）..."
  kill "$pid" 2>/dev/null || true
  for _ in $(seq 1 30); do is_running || break; sleep 1; done
  rm -f "$PID_FILE"
  echo "已停止"
}

do_status() {
  if ! nc -z 127.0.0.1 9092 2>/dev/null; then
    echo "Kafka 未运行"; return 0
  fi
  if is_running; then
    echo "Kafka 运行中：${BOOTSTRAP}（pid $(cat "$PID_FILE")）"
  else
    echo "Kafka 端口就绪：${BOOTSTRAP}（非本脚本启动，无 pid 文件）"
  fi
}

do_init_topics() {
  require_home
  nc -z 127.0.0.1 9092 2>/dev/null || die "Kafka 未运行，请先执行 start"
  for topic in "${TOPICS[@]}"; do
    if "$KAFKA_HOME/bin/kafka-topics.sh" --list --bootstrap-server "$BOOTSTRAP" | grep -qx "$topic"; then
      echo "主题已存在：$topic"
    else
      "$KAFKA_HOME/bin/kafka-topics.sh" --create --topic "$topic" \
        --partitions 3 --replication-factor 1 --bootstrap-server "$BOOTSTRAP" >/dev/null
      echo "已创建主题：$topic"
    fi
  done
}

do_logs() { tail -n 50 -f "$LOG_FILE"; }

case "${1:-}" in
  start)       do_start ;;
  stop)        do_stop ;;
  restart)     do_stop; do_start ;;
  status)      do_status ;;
  init-topics) do_init_topics ;;
  logs)        do_logs ;;
  *) echo "用法：$0 {start|stop|restart|status|init-topics|logs}"; exit 1 ;;
esac
