#!/usr/bin/env bash
#
# 灵犀智能平台 —— 离线一键安装/部署脚本（M5-5.4）
#
# 作用：在离线目标机上完成「前置检查 → 解包安装 → 依赖就绪探测 → 启动 → 健康校验 → 交付报告」。
# 幂等：重复执行不破坏已安装内容；--dry-run 仅做检查与打印计划，不落地安装、不启动服务。
#
# 用法：
#   bash install-offline.sh                        # 对当前解包目录就地安装（默认 /opt/lingxi）
#   bash install-offline.sh --dry-run              # 只检查 + 输出报告（可用于仓库内自检）
#   bash install-offline.sh --bundle x.tar.gz      # 从 tar 包安装（先校验再解包）
#   bash install-offline.sh --install-dir /opt/lx  # 覆盖安装目录
#   bash install-offline.sh --src /path/to/bundle  # 指定已解包目录
#   bash install-offline.sh --no-start             # 只安装不启动
#
# 环境变量：LINGXI_INSTALL_DIR 覆盖安装目录
#
# 报告：<bundle>/deploy/offline/reports/install-<时间戳>.md

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 兼容两种运行形态：解包目录顶层（lib/ 同级）与仓库内（deploy/offline/lib/）
if [ -f "$SCRIPT_DIR/lib/common.sh" ]; then
  . "$SCRIPT_DIR/lib/common.sh"
elif [ -f "$SCRIPT_DIR/deploy/offline/lib/common.sh" ]; then
  . "$SCRIPT_DIR/deploy/offline/lib/common.sh"
else
  echo "错误：未找到 deploy/offline/lib/common.sh" >&2; exit 1
fi

# ---------- 参数 ----------
DRY_RUN=0; NO_START=0; BUNDLE_ARG=""; SRC_ARG=""
INSTALL_DIR="${LINGXI_INSTALL_DIR:-/opt/lingxi}"
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run)     DRY_RUN=1 ;;
    --no-start)    NO_START=1 ;;
    --bundle)      BUNDLE_ARG="${2:-}"; shift ;;
    --src)         SRC_ARG="${2:-}"; shift ;;
    --install-dir) INSTALL_DIR="${2:-}"; shift ;;
    -h|--help)     sed -n '3,17p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
  shift
done

# ---------- 解析交付包根目录 ----------
TMP_EXTRACT=""
if [ -n "$BUNDLE_ARG" ]; then
  [ -f "$BUNDLE_ARG" ] || { echo "错误：找不到 tar 包 $BUNDLE_ARG" >&2; exit 1; }
  TMP_EXTRACT="$(mktemp -d)"
  SRCS_DIR="$TMP_EXTRACT"
  info "解包 tar 到临时目录：$TMP_EXTRACT"
  tar -xzf "$BUNDLE_ARG" -C "$TMP_EXTRACT" || { echo "错误：解包失败" >&2; exit 1; }
  # tar 内含单层包目录（lingxi-offline-*），取其唯一子目录作为交付包根
  SRCS_DIR="$TMP_EXTRACT/$(ls "$TMP_EXTRACT" | head -1)"
elif [ -n "$SRC_ARG" ]; then
  SRCS_DIR="$(cd "$SRC_ARG" && pwd)"
elif [ -f "$SCRIPT_DIR/SHA256SUMS" ]; then
  SRCS_DIR="$SCRIPT_DIR"                                  # 解包目录顶层运行
elif [ -f "$SCRIPT_DIR/manifest.yaml" ] && [ -f "$SCRIPT_DIR/../../Makefile" ]; then
  SRCS_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"             # 仓库内运行（deploy/offline/…）
else
  SRCS_DIR="$SCRIPT_DIR"
fi
MANIFEST="$SRCS_DIR/manifest.yaml"
# 解包交付包时清单在包根；仓库内运行时清单在 deploy/offline/ 下，故做一次回退查找
[ -f "$MANIFEST" ] || MANIFEST="$SRCS_DIR/deploy/offline/manifest.yaml"
[ -f "$MANIFEST" ] || { echo "错误：未找到 manifest.yaml（源目录 ${SRCS_DIR}）" >&2; exit 1; }

TS="$(date +%Y%m%d-%H%M%S)"
REPORT_DIR="$SRCS_DIR/deploy/offline/reports"
REPORT="$REPORT_DIR/install-$TS.md"
mkdir -p "$REPORT_DIR"
cleanup() { [ -n "$TMP_EXTRACT" ] && rm -rf "$TMP_EXTRACT"; }
trap cleanup EXIT

# ---------- 结果累积（供报告） ----------
ROWS="$(mktemp)"; PROBLEMS="$(mktemp)"
trap 'rm -f "$ROWS" "$PROBLEMS"; cleanup' EXIT
# $1 检查项 $2 状态(通过/警告/失败) $3 说明
chk() {
  printf '| %s | %s | %s |\n' "$1" "$2" "$3" >> "$ROWS"
  case "$2" in
    通过) ok   "$1 — $3" ;;
    警告) warn "$1 — $3" ;;
    失败) fail "$1 — $3"; printf '%s：%s\n' "$1" "$3" >> "$PROBLEMS" ;;
  esac
}
cmd_of() { command -v "$1" 2>/dev/null; }

echo "灵犀离线安装：$( [ "$DRY_RUN" = 1 ] && echo '[dry-run 模式]' )"
echo "交付包源：$SRCS_DIR"
echo "安装目录：$INSTALL_DIR"
echo "时间：$(date '+%Y-%m-%d %H:%M:%S')  主机：$(hostname)"

# ---------- 1. 前置检查 ----------
step "1. 前置检查"

# 1.1 tar 包完整性（仓库内自检无清单，跳过）
if [ -f "$SRCS_DIR/SHA256SUMS" ]; then
  if ( cd "$SRCS_DIR" && sha256_verify SHA256SUMS >/dev/null 2>&1 ); then
    chk "SHA256SUMS 完整性" 通过 "全部校验值匹配"
  else
    chk "SHA256SUMS 完整性" 失败 "存在校验不匹配文件——包可能损坏，请重新获取"
  fi
else
  chk "SHA256SUMS 完整性" 警告 "源目录无 SHA256SUMS（仓库内自检模式，跳过）"
fi

# 1.2 必需命令（java 为 core-java 运行所需；docker/node 视中间件可选性放宽）
for t in bash tar curl python3; do
  if cmd_of "$t" >/dev/null; then chk "命令 $t" 通过 "$(cmd_of "$t")"; else chk "命令 $t" 失败 "未找到，需先安装"; fi
done
if cmd_of java >/dev/null; then chk "命令 java" 通过 "$(java -version 2>&1 | head -1)"; else chk "命令 java" 失败 "未找到 JDK（core-java 必需）"; fi
if cmd_of docker >/dev/null; then chk "命令 docker" 通过 "$(docker --version 2>&1 | head -1)"; else chk "命令 docker" 警告 "未找到，Milvus/MinIO 栈需 docker"; fi
if cmd_of node >/dev/null; then chk "命令 node" 通过 "$(node --version)"; else chk "命令 node" 警告 "未找到，前端建议改用 nginx 托管 dist"; fi

# 1.3 安装目录权限
parent="$(dirname "$INSTALL_DIR")"
if [ -w "$parent" ] 2>/dev/null || { [ -d "$INSTALL_DIR" ] && [ -w "$INSTALL_DIR" ]; }; then
  chk "安装目录可写" 通过 "${INSTALL_DIR}（父目录 ${parent}）"
else
  chk "安装目录可写" 失败 "$parent 不可写（需 sudo 或改 --install-dir）"
fi

# 1.4 磁盘空间（要求 ≥ 2GB 可用）
avail="$(df -m "$parent" 2>/dev/null | awk 'NR==2{print $4}')"
if [ -n "$avail" ] && [ "$avail" -ge 2048 ]; then
  chk "磁盘可用空间" 通过 "${avail}MB 可用"
else
  chk "磁盘可用空间" 警告 "${avail:-未知}MB 可用（建议 ≥2048MB）"
fi

# ---------- 2. 产物与中间件就绪探测（清单驱动） ----------
step "2. 产物完整性 + 中间件就绪探测"
while IFS='|' read -r name kind required port version health probe artifact env; do
  [ -n "$name" ] || continue
  if [ "$kind" = "artifact" ]; then
    # 产物完整性与可运行性
    case "$probe" in
      bin)  target="$SRCS_DIR/$artifact"; [ -x "$target" ] ;;
      jar)  target="$SRCS_DIR/$artifact"; [ -f "$target" ] ;;
      dir)  target="$SRCS_DIR/$artifact"; [ -d "$target" ] ;;
      dist) target="$SRCS_DIR/$artifact/index.html"; [ -f "$target" ] ;;
      *)    target="$SRCS_DIR/$artifact"; [ -e "$target" ] ;;
    esac && state=通过 || state=失败
    if [ "$state" = 通过 ]; then
      chk "产物 $name:$version" "$state" "$artifact"
    else
      [ "$required" = true ] && chk "产物 $name:$version" 失败 "缺失 $artifact" \
                             || chk "产物 $name:$version" 警告 "缺失 ${artifact}（非必需）"
    fi
  else
    # 中间件：required=true 必须可达，否则失败；非必需仅警告
    case "$probe" in
      http) probe_http "$health" 3 && reach=1 || reach=0 ;;
      tcp)  port_open "${port}" && reach=1 || reach=0 ;;
      *)    reach=1 ;;
    esac
    if [ "$reach" = 1 ]; then
      chk "中间件 $name:$port" 通过 "${health:-tcp:$port} 可达"
    else
      [ "$required" = true ] && chk "中间件 $name:$port" 失败 "${health:-tcp:$port} 不可达" \
                             || chk "中间件 $name:$port" 警告 "不可达（非必需，可关闭对应功能）"
    fi
  fi

  # 业务端口是否已被占用（已运行的实例会被 start-all.sh 跳过）
  if [ "$kind" = "artifact" ] && [ "${port:-0}" -gt 0 ] 2>/dev/null; then
    if port_open "$port"; then
      chk "端口 ${port}（${name}）" 警告 "已被占用——若为本机既有实例将被跳过"
    else
      chk "端口 ${port}（${name}）" 通过 "空闲"
    fi
  fi
done < <(manifest_components "$MANIFEST")

# ---------- 3. 环境变量与配置文件 ----------
step "3. 环境变量与配置"
ENV_FILE="$SRCS_DIR/config/.env"
[ -f "$ENV_FILE" ] && chk "配置文件 config/.env" 通过 "$ENV_FILE 已存在" \
                   || chk "配置文件 config/.env" 警告 "未找到，需由 config/.env.example 复制并填写"
env_missing=""
while IFS='|' read -r name kind _req _port _ver _h _p _a env; do
  [ -n "$env" ] || continue
  IFS=',' read -r -a names <<< "$env"
  for var in "${names[@]}"; do
    [ -n "$var" ] || continue
    if [ -n "$(printenv "$var" 2>/dev/null || true)" ]; then continue; fi
    if [ -f "$ENV_FILE" ] && grep -qE "^[[:space:]]*${var}=" "$ENV_FILE"; then continue; fi
    env_missing="$env_missing $var"
  done
done < <(manifest_components "$MANIFEST")
if [ -n "$env_missing" ]; then
  # 去重（多个组件可能声明同名变量，如 LINGXI_JWT_SECRET）并排序，便于阅读
  env_missing="$(printf '%s\n' $env_missing | sort -u | tr '\n' ' ')"
  chk "必需环境变量" 警告 "未提供：${env_missing}（将沿用启动脚本默认值，生产必须显式设置）"
else
  chk "必需环境变量" 通过 "均已提供"
fi

# ---------- 4. 安装与启动（dry-run 仅打印计划） ----------
step "4. 安装与启动"
if [ "$DRY_RUN" = 1 ]; then
  chk "安装（解包落地）" 通过 "计划：将 $SRCS_DIR 复制到 ${INSTALL_DIR}（含 scripts/deploy/config/四端产物）"
  chk "启动服务" 通过 "计划：在 $INSTALL_DIR 执行 scripts/start-all.sh（幂等，跳过已在运行组件）"
else
  if [ "$SRCS_DIR" != "$INSTALL_DIR" ]; then
    [ -e "$INSTALL_DIR" ] && warn "安装目录已存在，将增量覆盖（幂等）"
    mkdir -p "$INSTALL_DIR"
    if tar -C "$SRCS_DIR" -cf - --exclude='./deploy/offline/dist' . | tar -C "$INSTALL_DIR" -xf -; then
      chk "安装（解包落地）" 通过 "$INSTALL_DIR"
    else
      chk "安装（解包落地）" 失败 "复制失败（检查权限或改用 sudo）"
    fi
  else
    chk "安装（解包落地）" 通过 "源目录即安装目录，跳过复制"
  fi

  if [ "$NO_START" = 1 ]; then
    chk "启动服务" 警告 "按 --no-start 跳过"
  elif [ -x "$INSTALL_DIR/scripts/start-all.sh" ] || [ -f "$INSTALL_DIR/scripts/start-all.sh" ]; then
    ( cd "$INSTALL_DIR" && bash scripts/start-all.sh ) && chk "启动服务" 通过 "start-all.sh 执行完成" \
                                                       || chk "启动服务" 失败 "start-all.sh 返回非零，见 logs/"
  else
    chk "启动服务" 失败 "未找到 scripts/start-all.sh"
  fi

  # 健康校验四端
  step "5. 健康校验"
  while IFS='|' read -r name kind required _port _ver health _probe _artifact _env; do
    [ "$kind" = "artifact" ] && [ -n "$health" ] || continue
    if wait_http "$health" 200 60; then
      chk "健康检查 $name" 通过 "$health"
    elif [ "$required" = true ]; then
      chk "健康检查 $name" 失败 "$health 未返回 200"
    else
      chk "健康检查 $name" 警告 "$health 未就绪（非必需）"
    fi
  done < <(manifest_components "$MANIFEST")
fi

# ---------- 6. 交付报告 ----------
step "6. 生成交付报告"
{
  echo "# 灵犀离线安装交付报告"
  echo
  echo "- 模式：$( [ "$DRY_RUN" = 1 ] && echo 'dry-run（仅检查，未落地/未启动）' || echo '实装' )"
  echo "- 交付包源：\`$SRCS_DIR\`"
  echo "- 安装目录：\`$INSTALL_DIR\`"
  echo "- 版本：$(manifest_version "$MANIFEST")"
  echo "- 执行主机：$(hostname)  时间：$(date '+%Y-%m-%d %H:%M:%S')"
  echo
  echo "## 检查结果"
  echo
  echo "| 检查项 | 结果 | 说明 |"
  echo "|---|---|---|"
  cat "$ROWS"
  echo
  echo "## 缺失/失败项"
  if [ -s "$PROBLEMS" ]; then sed 's/^/- /' "$PROBLEMS"; else echo "（无）"; fi
  echo
  echo "## 后续人工步骤"
  cat <<'EOS'
- 若 `config/.env` 缺失：复制 `config/.env.example` 为 `config/.env`，填写 JWT 密钥、管理员初始口令、数据库口令、LLM/Embedding API Key。
- 中间件不走交付包内安装：MySQL/Redis/Kafka/Milvus/Nacos 需目标机预先就绪（见 deploy/offline/README.md 前置依赖清单）。
- Milvus 栈若无镜像：先在目标机 `docker load` 离线镜像 tar，再 `docker compose -f docker-compose.offline.yml up -d`（禁 docker pull）。
- 前端若不使用 Vite dev server：用 `config/nginx.conf.example` 托管 `web-vue3/dist`，并替换其中的安装目录占位符。
- 冒烟验证：访问 `http://<目标机IP>:3000`，管理员账号 `LINGXI_ADMIN_USERNAME` / `LINGXI_ADMIN_INIT_PASSWORD`。
EOS
} > "$REPORT"
ok "报告已写入：$REPORT"

if [ -s "$PROBLEMS" ]; then
  warn "存在 $(wc -l < "$PROBLEMS" | tr -d ' ') 项失败/缺失，详见报告"
  exit 1
fi
info "全部检查通过"
