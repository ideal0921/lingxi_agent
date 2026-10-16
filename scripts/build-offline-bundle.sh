#!/usr/bin/env bash
#
# 灵犀智能平台 —— 离线交付包构建脚本（M5-5.4）
#
# 作用：把「四端产物 + 脚本/配置/文档 + 清单」收集成一个可离线拷贝的目录，并打包为 tar.gz。
# 输出：deploy/offline/dist/lingxi-offline-<版本>-<时间戳>/ 与同名 .tar.gz
# 日志：deploy/offline/dist/build-<时间戳>.log（stdout 同时落盘）
#
# 设计要点：
#   1. **清单驱动**：组件产物按 deploy/offline/manifest.yaml 的 components.artifact 收集，
#      附带目录按 assets 段的 src→dest 复制，避免在脚本里硬编码路径；
#   2. **缺失零静默**：每项打印「已包含/缺失」，结尾汇总缺失清单并写入 bundle-info.txt；
#   3. **不依赖联网**：不执行 docker pull；前端/网关产物优先复用已有构建结果；
#   4. **幂等**：每次运行生成独立时间戳目录，可反复执行。
#
# 用法：bash scripts/build-offline-bundle.sh
# 环境变量：
#   LINGXI_BUNDLE_VERSION        覆盖版本号（默认取 manifest.yaml 顶层 version）
#   LINGXI_WHEELS_DOWNLOAD       置 1 时尝试 pip download 打包 Python wheels（默认尝试，失败仅记录）
#   LINGXI_WHEELS_TIMEOUT        wheels 下载硬超时秒数（默认 300，防离线网络挂起）

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$ROOT_DIR/deploy/offline/manifest.yaml"
# shellcheck source=../deploy/offline/lib/common.sh
. "$ROOT_DIR/deploy/offline/lib/common.sh"

VERSION="${LINGXI_BUNDLE_VERSION:-$(manifest_version "$MANIFEST")}"
[ -n "$VERSION" ] || VERSION="0.0.0"
TS="$(date +%Y%m%d-%H%M%S)"
DIST_DIR="$ROOT_DIR/deploy/offline/dist"
BUNDLE_NAME="lingxi-offline-$VERSION-$TS"
# 先在系统临时目录完成构建，再整体移入 dist/。
# 原因：dist/ 是输出目录（可能含历史交付包）；若直接在其中构建，复制 deploy/ 时会读到自身，
# 造成「包套包」的无限递归（已在开发期实际踩到），故用外部暂存目录彻底规避。
STAGE_ROOT="$(mktemp -d)"
OUT="$STAGE_ROOT/$BUNDLE_NAME"
LOG="$DIST_DIR/build-$TS.log"
mkdir -p "$DIST_DIR"
# dist/ 内是构建产物（含上百 MB 的 tar 包），不入库；此 .gitignore 由脚本幂等生成
[ -f "$DIST_DIR/.gitignore" ] || printf '*\n!.gitignore\n' > "$DIST_DIR/.gitignore"

# stdout/stderr 全部落盘：便于事后核对「缺失项」与各构建命令输出
exec > >(tee -a "$LOG") 2>&1

# 缺失/跳过项累积（结尾汇总），用临时文件而非数组以便跨函数追加
MISS_FILE="$(mktemp)"; INC_FILE="$(mktemp)"
cleanup() { rm -f "$MISS_FILE" "$INC_FILE"; rm -rf "$STAGE_ROOT"; }
trap cleanup EXIT

inc()  { printf '%s\t%s\n' "$1" "$2" >> "$INC_FILE"; ok "已包含 $1（$2）"; }
miss() { printf '%s\n' "$1" >> "$MISS_FILE"; fail "缺失  $1"; }
# 复制单个产物并保持 bundle 内仓库同构路径（供 scripts/start-all.sh 直接复用）
copy_artifact() { # $1=组件名 $2=仓库内相对路径 $3=bundle 内相对路径(默认同 $2)
  # 注意：bash 3.2 在同一 local 行内引用前面刚声明的变量会触发 set -u 未绑定，故拆成多行赋值
  local name="$1" rel="$2"
  local dest="${3:-$2}"
  [ -n "$rel" ] || { miss "${name}（清单 artifact 为空，跳过）"; return 1; }
  local src="$ROOT_DIR/$rel"
  [ -e "$src" ] || { miss "${rel}（${name}）"; return 1; }
  mkdir -p "$OUT/$(dirname "$dest")"
  cp -R "$src" "$OUT/$dest"
  inc "$dest" "$name $(du -sh "$src" 2>/dev/null | cut -f1)"
}

echo "构建离线交付包：$BUNDLE_NAME"
echo "仓库：$ROOT_DIR"
echo "时间：$(date '+%Y-%m-%d %H:%M:%S')  主机：$(hostname)"
mkdir -p "$OUT"

# ---------- 1. 业务组件产物（清单驱动，特殊项现构建） ----------
step "收集业务组件产物"
while IFS='|' read -r name kind _required _port _ver _health probe artifact _env; do
  [ "$kind" = "artifact" ] || continue
  case "$probe" in
    bin)
      # Go 网关：无二进制则现编译（离线机器只拷贝二进制，不需 Go 工具链）
      if [ ! -x "$ROOT_DIR/$artifact" ]; then
        warn "$artifact 不存在，尝试现编译（go build）..."
        if ( cd "$ROOT_DIR/gateway-go" && go build -o bin/gateway ./cmd/gateway ); then
          ok "网关编译完成"
        else
          fail "网关编译失败"; fi
      fi
      copy_artifact "$name" "$artifact" || true
      ;;
    jar)
      # Java：按 manifest 路径不存在时，回退匹配同目录下的 SNAPSHOT jar
      if [ ! -f "$ROOT_DIR/$artifact" ]; then
        local_jar="$(ls "$ROOT_DIR"/core-java/start/target/lingxi-start-*-SNAPSHOT.jar 2>/dev/null | head -1 || true)"
        if [ -n "$local_jar" ]; then
          rel="${local_jar#"$ROOT_DIR"/}"
          warn "$artifact 未命中，改用实际 jar：${rel}（建议同步 manifest.yaml）"
          copy_artifact "$name" "$rel" || true
        else
          miss "${artifact}（${name}；可先执行：cd core-java && mvn -DskipTests package）"
        fi
      else
        copy_artifact "$name" "$artifact" || true
      fi
      ;;
    dist)
      # 前端：无 dist 则现构建（Vite 静态产物，离线机用 nginx 托管）
      if [ ! -f "$ROOT_DIR/web-vue3/dist/index.html" ]; then
        warn "web-vue3/dist 缺失，尝试 npm run build ..."
        if ( cd "$ROOT_DIR/web-vue3" && npm run build ); then ok "前端构建完成"; else fail "前端构建失败"; fi
      fi
      copy_artifact "$name" "$artifact" || true
      ;;
    dir)
      # Python AI 域：拷贝可运行所需源码子集（排除 .venv/tests/egg-info），保证离线机可启动
      src_dir="$ROOT_DIR/$artifact"
      if [ -d "$src_dir" ]; then
        dest_dir="$OUT/$artifact"
        mkdir -p "$dest_dir"
        tar -C "$src_dir" -cf - \
          --exclude='./.venv' --exclude='./tests' --exclude='./*.egg-info' \
          --exclude='./.pytest_cache' --exclude='./__pycache__' . | tar -C "$dest_dir" -xf -
        inc "$artifact" "$name 源码 $(du -sh "$dest_dir" | cut -f1)"
      else
        miss "${artifact}（${name}）"
      fi
      ;;
    file)
      copy_artifact "$name" "$artifact" || true
      ;;
    *)
      copy_artifact "$name" "$artifact" || true
      ;;
  esac
done < <(manifest_components "$MANIFEST")

# ---------- 2. Python 依赖（requirements 或冻结清单 + 可选 wheels 离线缓存） ----------
step "收集 Python 依赖"
WHEELS_DIR="$OUT/ai-python/wheels"; mkdir -p "$WHEELS_DIR"
REQ_SRC="$(ls "$ROOT_DIR"/ai-python/requirements*.txt 2>/dev/null | head -1 || true)"
if [ -n "$REQ_SRC" ]; then
  cp "$REQ_SRC" "$OUT/ai-python/$(basename "$REQ_SRC")"
  inc "ai-python/$(basename "$REQ_SRC")" "requirements 清单"
  REQ_FILE="$REQ_SRC"
elif [ -x "$ROOT_DIR/ai-python/.venv/bin/pip" ]; then
  # 仓库无 requirements*.txt（工程用 pyproject.toml）：用当前 venv 冻结出可复现清单
  if "$ROOT_DIR/ai-python/.venv/bin/pip" freeze --all > "$OUT/ai-python/requirements-frozen.txt" 2>/dev/null; then
    cp "$ROOT_DIR/ai-python/pyproject.toml" "$OUT/ai-python/pyproject.toml"
    inc "ai-python/requirements-frozen.txt" "venv 冻结清单（工程无 requirements*.txt，改用 pip freeze）"
    REQ_FILE="$OUT/ai-python/requirements-frozen.txt"
  else
    miss "ai-python 依赖清单（pip freeze 失败）"; REQ_FILE=""
  fi
else
  miss "ai-python 依赖清单（无 requirements*.txt 且无 .venv/bin/pip）"; REQ_FILE=""
fi

# wheels 离线缓存：源机联网时预下载；离线源机失败属预期，如实记录后继续
if [ -n "$REQ_FILE" ] && [ "${LINGXI_WHEELS_DOWNLOAD:-1}" = "1" ]; then
  warn "尝试 pip download 到 wheels/（离线网络可能失败，属预期）..."
  pip_bin="$ROOT_DIR/ai-python/.venv/bin/pip"
  if [ ! -x "$pip_bin" ]; then pip_bin="$(command -v pip3 || command -v pip || true)"; fi
  if [ -n "$pip_bin" ] && run_with_timeout "${LINGXI_WHEELS_TIMEOUT:-300}" \
       "$pip_bin" download -r "$REQ_FILE" -d "$WHEELS_DIR" --no-cache-dir --timeout 15 --retries 1; then
    wcount="$(ls -1 "$WHEELS_DIR" 2>/dev/null | wc -l | tr -d ' ')"
    if [ "$wcount" -gt 0 ]; then inc "ai-python/wheels" "离线 wheel 包 ×$wcount"; else miss "ai-python/wheels（下载无产物）"; fi
  else
    miss "ai-python/wheels（pip download 失败/超时——需联网环境预打包）"
  fi
fi

# ---------- 3. 附带目录/文件（清单 assets 段） ----------
step "复制脚本/配置/文档"
while IFS='|' read -r src dest optional; do
  [ -n "$src" ] || continue
  srcp="$ROOT_DIR/$src"
  echo "  · $src → $dest"
  if [ ! -e "$srcp" ]; then
    if [ "$optional" = "true" ]; then warn "跳过（不存在且可选）$src → $dest"; else miss "${src}（必需资产）"; fi
    continue
  fi
  mkdir -p "$OUT/$(dirname "$dest")"
  case "$dest" in
    # deploy/ 整体复制：用 cp 后显式删除 offline/dist，避免把交付包自身（或历史包）打包进去
    deploy) cp -R "$srcp" "$OUT/$dest"; rm -rf "$OUT/$dest/offline/dist" ;;
    *)      cp -R "$srcp" "$OUT/$dest" ;;
  esac
  inc "$dest" "$src"
done < <(manifest_assets "$MANIFEST")

# ---------- 4. 清单文件（SHA256SUMS 与 bundle-info.txt） ----------
step "生成校验清单与包信息"
{
  echo "灵犀智能平台 离线交付包 —— 构建信息"
  echo "版本        : $VERSION"
  echo "构建时间    : $(date '+%Y-%m-%d %H:%M:%S')"
  echo "构建主机    : $(hostname)  ($(uname -sm))"
  echo "包目录      : $BUNDLE_NAME"
  echo "清单来源    : deploy/offline/manifest.yaml"
  echo
  echo "—— 已包含产物 ——"
  cut -f1,2 "$INC_FILE"
  echo
  echo "—— 缺失/失败项（需人工处理）——"
  if [ -s "$MISS_FILE" ]; then cat "$MISS_FILE"; else echo "（无）"; fi
} > "$OUT/bundle-info.txt"
inc "bundle-info.txt" "构建信息"

# SHA256SUMS：相对包根路径生成，校验时在该目录内执行 sha256sum -c / shasum -a 256 -c。
# 注意：xargs 以 execvp 调用命令、无法解析 shell 函数，故这里解析出真实二进制（如 shasum -a 256）。
if command -v sha256sum >/dev/null 2>&1; then SHA_CMD="sha256sum"; else SHA_CMD="shasum -a 256"; fi
( cd "$OUT" && find . -type f ! -name SHA256SUMS | sort | xargs $SHA_CMD > SHA256SUMS )
inc "SHA256SUMS" "$(grep -c . "$OUT/SHA256SUMS") 个文件校验值"

# ---------- 5. 移入 dist/ 并打包 ----------
step "移入 dist/ 并打包 tar.gz"
TARBALL="$DIST_DIR/$BUNDLE_NAME.tar.gz"
mv "$OUT" "$DIST_DIR/$BUNDLE_NAME" || { fail "移入 dist/ 失败"; exit 1; }
OUT="$DIST_DIR/$BUNDLE_NAME"
tar -czf "$TARBALL" -C "$DIST_DIR" "$BUNDLE_NAME" || { fail "打包失败"; exit 1; }
sha256_of "$TARBALL" > "$TARBALL.sha256"
ok "tarball: ${TARBALL}（$(du -sh "$TARBALL" | cut -f1)，校验值见 .sha256）"

# ---------- 6. 汇总 ----------
step "构建汇总"
echo "包目录  : ${OUT}（$(du -sh "$OUT" | cut -f1)）"
echo "tar 包  : ${TARBALL}（$(du -sh "$TARBALL" | cut -f1)）"
echo "日志    : $LOG"
echo "缺失清单（共 $(wc -l < "$MISS_FILE" | tr -d ' ') 项）："
if [ -s "$MISS_FILE" ]; then sed 's/^/  - /' "$MISS_FILE"; else echo "  - （无）"; fi
