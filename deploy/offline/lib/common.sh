#!/usr/bin/env bash
#
# 灵犀私有化离线交付 —— 共享工具库（build-offline-bundle.sh 与 install-offline.sh 共用）
# 仅提供四类能力：日志着色、外部命令探测、端口/HTTP 探活、manifest.yaml 行式解析。
# 兼容 macOS 自带 bash 3.2：不使用关联数组 / mapfile / ${var,,} 等新特性。

# ---------- 日志 ----------
info() { printf '\033[36m[信息]\033[0m %s\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
fail() { printf '  \033[31m✗\033[0m %s\n' "$*"; }
step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

# ---------- 外部命令探测 ----------
# curl：受限 shell 的 PATH 可能不含 /usr/bin，故显式解析（可用 CURL_BIN 覆盖）
LX_CURL="${CURL_BIN:-$(command -v curl 2>/dev/null || echo /usr/bin/curl)}"

# SHA-256：Linux 多为 sha256sum，macOS 只有 shasum -a 256；两者输出格式均为 "<hash>  <path>"
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi
}
# 校验清单：须先 cd 到 SHA256SUMS 所在目录再调用
sha256_verify() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum -c "$1"; else shasum -a 256 -c "$1"; fi
}

# 带超时执行命令（macOS 无 timeout 命令，故用看门狗子进程实现）
run_with_timeout() {
  local secs="$1"; shift
  "$@" & local pid=$!
  ( sleep "$secs"; kill -0 "$pid" 2>/dev/null && kill -TERM "$pid" 2>/dev/null ) & local watcher=$!
  wait "$pid"; local rc=$?
  kill "$watcher" 2>/dev/null
  return $rc
}

# ---------- 探活 ----------
# 端口是否被监听：IPv4 与 IPv6 各试一次（Vite 等只监听 ::1，仅探 127.0.0.1 会误判）
port_open() {
  local port="$1"
  if command -v python3 >/dev/null 2>&1; then
    python3 - "$port" <<'PY' 2>/dev/null
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
  else
    nc -z 127.0.0.1 "$port" >/dev/null 2>&1
  fi
}

http_code() { "$LX_CURL" -s -m "${2:-3}" -o /dev/null -w '%{http_code}' "$1" 2>/dev/null || echo 000; }

# HTTP 探活：$1 URL、$2 探测超时秒（默认 3）。中间件就绪接受 2xx/3xx
# （如 Nacos 的 /nacos 会 302 跳转登录页，只认 200 会误判为不可达）
probe_http() {
  local health="$1" timeout="${2:-3}"
  [ -n "$health" ] || return 1
  case "$(http_code "$health" "$timeout")" in
    2*|3*) return 0 ;;
    *)     return 1 ;;
  esac
}

wait_http() {
  local url="$1" expect="${2:-200}" timeout="${3:-60}" waited=0
  while [ "$waited" -lt "$timeout" ]; do
    [ "$(http_code "$url" 2)" = "$expect" ] && return 0
    sleep 2; waited=$((waited + 2))
  done
  return 1
}

# ---------- manifest.yaml 解析 ----------
# manifest 采用「行式扁平」结构：组件以 "  - name: X" 起，字段行 "    key: value"，
# 便于 bash 直接解析（字段清单见 manifest.yaml 头部注释）。
# 输出以 "|" 分隔：name|kind|required|port|version|health|probe|artifact|env
# 为何不用制表符：bash 的 read 把制表符视为「空白」，连续制表符会被折叠，
# 导致空字段（如 middleware 的 artifact、schema 的 health）错位；竖线非空白，可保留空字段。
manifest_components() {
  awk '
    function clean(s){ gsub(/^[ \t]+/,"",s); gsub(/[ \t]+$/,"",s); gsub(/^"/,"",s); gsub(/"$/,"",s); return s }
    function emit(){ if (name != "") printf "%s|%s|%s|%s|%s|%s|%s|%s|%s\n", name, kind, required, port, version, health, probe, artifact, env }
    {
      pos = index($0, ":"); if (pos == 0) next
      key = clean(substr($0, 1, pos - 1)); val = clean(substr($0, pos + 1))
      gsub(/^-[ \t]*/, "", key)
      if      (key == "name")     { emit(); name = val; kind = required = port = version = health = probe = artifact = env = "" }
      else if (key == "kind")     kind = val
      else if (key == "required") required = val
      else if (key == "port")     port = val
      else if (key == "version")  version = val
      else if (key == "health")   health = val
      else if (key == "probe")    probe = val
      else if (key == "artifact") artifact = val
      else if (key == "env")      env = val
    }
    END{ emit() }
  ' "$1"
}

# 输出 assets 段以 "|" 分隔：src|dest|optional（供打包脚本按清单收集文件）
manifest_assets() {
  awk '
    function clean(s){ gsub(/^[ \t]+/,"",s); gsub(/[ \t]+$/,"",s); gsub(/^"/,"",s); gsub(/"$/,"",s); return s }
    function emit(){ if (src != "") printf "%s|%s|%s\n", src, dest, optional }
    {
      pos = index($0, ":"); if (pos == 0) next
      key = clean(substr($0, 1, pos - 1)); val = clean(substr($0, pos + 1))
      gsub(/^-[ \t]*/, "", key)
      if      (key == "src")      { emit(); src = val; dest = ""; optional = "false" }
      else if (key == "dest")     dest = val
      else if (key == "optional") optional = val
    }
    END{ emit() }
  ' "$1"
}

# 读取顶层 version（组件内的 version 带缩进，不会匹配行首锚点）
manifest_version() {
  awk 'function clean(s){ gsub(/^[ \t]+/,"",s); gsub(/[ \t]+$/,"",s); gsub(/^"/,"",s); gsub(/"$/,"",s); return s }
       /^version:/ { print clean(substr($0, index($0, ":") + 1)); exit }' "$1"
}
