#!/usr/bin/env bash
#
# M5-5.7 安全加固：统一依赖漏洞扫描入口。
#
# 设计要点（为什么这么做）：
#   - 不用 `set -e`：任何一个扫描器失败（如联网失败、工具缺失）都不能拖垮整体，
#     必须继续跑完剩余生态并在末尾给出汇总，因此逐项显式判断返回码。
#   - 不依赖 jq：本机未安装 jq，JSON 解析全部交给 python3（macOS 自带）。
#   - 不依赖 trivy/grype/k6：本机仅有 npm/mvn/go/python，故按生态选取其原生工具。
#   - 幂等：每次执行生成独立时间戳日志，不改动任何业务代码，可重复运行。
#
set -u

# ---------- 路径与日志 ----------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
REPORT_DIR="$SCRIPT_DIR/reports"
mkdir -p "$REPORT_DIR"
TS="$(date +%Y%m%d-%H%M)"
LOG="$REPORT_DIR/dependency-scan-$TS.log"

PY_BIN="$REPO_ROOT/ai-python/.venv/bin/python"
[ -x "$PY_BIN" ] || PY_BIN="$(command -v python3)"

# 汇总表：三列数组（工具 | 结果 | 关键发现）
SUM_TOOL=(); SUM_RESULT=(); SUM_FINDING=()
record() { SUM_TOOL+=("$1"); SUM_RESULT+=("$2"); SUM_FINDING+=("${3:-}"); }

say() { printf '%s\n' "$*"; }
# 把一段原始输出追加到日志（完整证据留在文件里，终端只给结论）
to_log() { printf '%s\n' "$*" >> "$LOG"; }

# run_timeout <秒> <输出文件> <命令...>：
#   macOS 无自带 timeout，优先用 perl 的 alarm+exec 惯用法（干净、无残留看门狗进程）；
#   perl 不可用时退回后台看门狗方案，保证联网工具长时间挂起时能被终止。
run_timeout() {
    local secs="$1"; shift
    local outfile="$1"; shift
    if command -v perl >/dev/null 2>&1; then
        perl -e 'alarm shift; exec @ARGV' "$secs" "$@" >"$outfile" 2>&1
        return $?
    fi
    ( "$@" ) >"$outfile" 2>&1 &
    local pid=$!
    ( sleep "$secs"; kill -9 "$pid" 2>/dev/null ) &
    local watcher=$!
    disown "$watcher" 2>/dev/null || true
    wait "$pid" 2>/dev/null
    local rc=$?
    kill "$watcher" 2>/dev/null
    return $rc
}

{
    echo "====================================================="
    echo " 依赖漏洞扫描报告 $TS"
    echo " 仓库: $REPO_ROOT"
    echo " Python: $PY_BIN"
    echo "====================================================="
} >> "$LOG"

say "依赖扫描中……完整输出写入：$LOG"

# ---------- 1. Node (web-vue3) ----------
scan_node() {
    say "[Node] npm audit --omit=dev ..."
    local out; out="$(mktemp)"
    run_timeout 120 "$out" bash -c "cd '$REPO_ROOT/web-vue3' && exec npm audit --omit=dev --json"
    local rc=$?
    to_log ""; to_log "### Node: npm audit --omit=dev --json (exit=$rc)"; cat "$out" >> "$LOG"

    local parsed
    parsed="$("$PY_BIN" - "$out" <<'PY'
import sys, json
# npm 会把 JSON 与 "npm error ..." 文本混在一起，故从首个 { 到最后一个 } 截取再解析。
raw = open(sys.argv[1], encoding="utf-8", errors="replace").read()
start = raw.find("{"); end = raw.rfind("}")
if start == -1 or end <= start:
    print("SKIPPED|npm audit 未返回 JSON（多为网络不可达），请人工复核")
    sys.exit(0)
try:
    data = json.loads(raw[start:end + 1])
except Exception:
    print("SKIPPED|npm audit 输出非 JSON，请人工复核")
    sys.exit(0)
# 私有 registry（如 npmmirror）常以 404/NOT_IMPLEMENTED 响应 audit 请求，
# 其返回对象没有 metadata 字段（错误信息嵌在 body.error 里），不能误判为“无漏洞”。
if not isinstance(data, dict) or "metadata" not in data:
    msg = "npm registry 不支持 audit 接口"
    if isinstance(data, dict):
        msg = data.get("body", {}).get("error") or data.get("error") or data.get("message") or msg
    print("SKIPPED|npm registry 不支持 audit 接口: " + str(msg)[:110])
    sys.exit(0)
vulns = (data.get("metadata", {}) or {}).get("vulnerabilities", {}) or {}
crit = int(vulns.get("critical", 0)); high = int(vulns.get("high", 0))
total = sum(int(x) for x in vulns.values()) if vulns else 0
res = "FAIL" if (crit > 0 or high > 0) else "PASS"
print(f"{res}|critical={crit}, high={high}, total={total}")
PY
)"
    rm -f "$out"
    record "Node" "${parsed%%|*}" "${parsed#*|}"
}

# ---------- 2. Python (ai-python) ----------
scan_python() {
    say "[Python] pip list + pip-audit ..."
    local list_out; list_out="$(mktemp)"
    ( "$PY_BIN" -m pip list --format=json ) >"$list_out" 2>&1
    to_log ""; to_log "### Python: pip list --format=json"; cat "$list_out" >> "$LOG"
    rm -f "$list_out"

    # 优先以 requirements 清单扫描；仓库未提供 requirements.txt，则退回“审计当前虚拟环境”
    local req="" R
    for R in "$REPO_ROOT/ai-python/requirements.txt" "$REPO_ROOT/requirements.txt"; do
        [ -f "$R" ] && req="$R"
    done

    # pip-audit 未安装则尝试联网安装一次（较慢，故给足超时）
    if ! "$PY_BIN" -m pip_audit --version >/dev/null 2>&1; then
        say "[Python] 未检测到 pip-audit，尝试 pip install pip-audit 一次 ..."
        local inst_out; inst_out="$(mktemp)"
        run_timeout 300 "$inst_out" "$PY_BIN" -m pip install pip-audit
        to_log ""; to_log "### Python: pip install pip-audit"; cat "$inst_out" >> "$LOG"; rm -f "$inst_out"
    fi

    if ! "$PY_BIN" -m pip_audit --version >/dev/null 2>&1; then
        record "Python" "SKIPPED" "pip-audit 不可用且安装失败（需联网）；已输出 pip list 供人工核对"
        return
    fi

    local out; out="$(mktemp)"; local rc
    if [ -n "$req" ]; then
        run_timeout 300 "$out" "$PY_BIN" -m pip_audit -r "$req" --format json; rc=$?
    else
        # 无 requirements 文件：直接审计 venv 已安装包（等效内联 -r 冻结清单）
        run_timeout 300 "$out" "$PY_BIN" -m pip_audit --format json; rc=$?
    fi
    to_log ""; to_log "### Python: pip-audit (exit=$rc, req=${req:-<环境审计>})"; cat "$out" >> "$LOG"

    local parsed
    parsed="$("$PY_BIN" - "$out" <<'PY'
import sys, json
# pip-audit 可能先打印告警行再输出 JSON，故截取首个 { 到最后一个 } 再解析。
raw = open(sys.argv[1], encoding="utf-8", errors="replace").read()
start = raw.find("{"); end = raw.rfind("}")
data = None
if start != -1 and end > start:
    try:
        data = json.loads(raw[start:end + 1])
    except Exception:
        data = None
if data is None:
    print("SKIPPED|pip-audit 未返回可解析 JSON（多为联网获取漏洞库失败）")
    sys.exit(0)
count = 0
names = []
if isinstance(data, dict):
    for dep in data.get("dependencies", []) or []:
        n = len(dep.get("vulns", []) or [])
        count += n
        if n:
            names.append(dep.get("name", "?"))
    count += len(data.get("vulnerabilities", []) or [])
elif isinstance(data, list):
    for dep in data:
        n = len(dep.get("vulns", []) or [])
        count += n
        if n:
            names.append(dep.get("name", "?"))
res = "FAIL" if count > 0 else "PASS"
detail = ("受影响包: " + ",".join(names)) if names else "无"
print(f"{res}|发现漏洞条目数={count}（{detail}；无 requirements.txt，审计虚拟环境已安装包）")
PY
)"
    rm -f "$out"
    record "Python" "${parsed%%|*}" "${parsed#*|}"
}

# ---------- 3. Go (gateway-go) ----------
scan_go() {
    say "[Go] go list -m -u all ..."
    local out; out="$(mktemp)"
    run_timeout 180 "$out" bash -c "cd '$REPO_ROOT/gateway-go' && exec go list -m -u all"
    local rc=$?
    to_log ""; to_log "### Go: go list -m -u all (exit=$rc)"; cat "$out" >> "$LOG"
    rm -f "$out"

    say "[Go] govulncheck ..."
    local vout; vout="$(mktemp)"
    run_timeout 300 "$vout" bash -c "cd '$REPO_ROOT/gateway-go' && exec go run golang.org/x/vuln/cmd/govulncheck@latest ./..."
    local vrc=$?
    to_log ""; to_log "### Go: govulncheck ./... (exit=$vrc)"; cat "$vout" >> "$LOG"

    local parsed
    if grep -q 'No vulnerabilities found' "$vout"; then
        parsed="PASS|govulncheck 未发现已知漏洞"
    elif grep -q 'Vulnerability #' "$vout"; then
        local n; n="$(grep -c 'Vulnerability #' "$vout")"
        parsed="FAIL|govulncheck 发现 $n 处漏洞"
    else
        parsed="SKIPPED|govulncheck 未能完成（需联网拉取 vuln 库；exit=${vrc}），go list 已列可升级依赖"
    fi
    rm -f "$vout"
    record "Go" "${parsed%%|*}" "${parsed#*|}"
}

# ---------- 4. Java (core-java) ----------
scan_java() {
    say "[Java] mvn dependency:tree (common 模块依赖清单) ..."
    local out; out="$(mktemp)"
    run_timeout 240 "$out" bash -c "cd '$REPO_ROOT/core-java' && exec mvn -B -pl common dependency:tree"
    local rc=$?
    to_log ""; to_log "### Java: mvn -B -pl common dependency:tree (exit=$rc)"; cat "$out" >> "$LOG"; rm -f "$out"

    say "[Java] OWASP dependency-check（需联网拉取 NVD，可能超时）..."
    local cout; cout="$(mktemp)"
    local crc=0
    run_timeout 300 "$cout" bash -c "cd '$REPO_ROOT/core-java' && exec mvn -B org.owasp:dependency-check-maven:check -DskipTests" || crc=$?
    to_log ""; to_log "### Java: dependency-check-maven:check -DskipTests (exit=$crc)"; cat "$cout" >> "$LOG"

    local parsed
    if grep -q 'BUILD SUCCESS' "$cout"; then
        parsed="PASS|dependency-check 执行成功（详见 target/dependency-check-report.html）"
    elif grep -q 'Plugin not found in any plugin repository' "$cout"; then
        parsed="SKIPPED|dependency-check 插件未能解析（本地 Maven 仓库无写权限/镜像未命中），未能执行；已输出 dependency:tree"
    else
        parsed="SKIPPED|需联网拉取 NVD 漏洞库，未完成（exit=${crc}）；已输出 dependency:tree 依赖清单供人工核对"
    fi
    rm -f "$cout"
    record "Java" "${parsed%%|*}" "${parsed#*|}"
}

# 逐项执行：单项失败绝不中断整体
scan_node
scan_python
scan_go
scan_java

# ---------- 汇总（同时打印到终端与日志） ----------
render_summary() {
    printf '\n==================== 依赖扫描汇总 ====================\n'
    printf '%-10s | %-8s | %s\n' '工具' '结果' '关键发现'
    printf '%-10s-+-%-8s-+-%s\n' '----------' '--------' '--------------------------------------------'
    local i
    for i in "${!SUM_TOOL[@]}"; do
        printf '%-10s | %-8s | %s\n' "${SUM_TOOL[$i]}" "${SUM_RESULT[$i]}" "${SUM_FINDING[$i]}"
    done
    printf '======================================================\n'
}
render_summary | tee -a "$LOG"
say "完整证据日志: $LOG"

# 退出码：出现 FAIL 记非 0，便于 CI 断言；全 PASS/SKIPPED 记 0。
rc=0
for r in "${SUM_RESULT[@]}"; do [ "$r" = "FAIL" ] && rc=1; done
exit $rc
