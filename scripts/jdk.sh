#!/usr/bin/env bash
# =============================================================================
# JDK 版本切换脚本（macOS）
# -----------------------------------------------------------------------------
# 功能：在已安装的 JDK 8 / 17 / 21 之间切换当前 shell 的 JAVA_HOME。
# 用法：
#   source scripts/jdk.sh 8     # 切换到 JDK 8
#   source scripts/jdk.sh 17    # 切换到 JDK 17
#   source scripts/jdk.sh 21    # 切换到 JDK 21（项目目标版本）
#   source scripts/jdk.sh       # 查看当前版本与可用版本
# 说明：必须在当前 shell 中 source 执行（export JAVA_HOME 才能生效）。
# =============================================================================

# 已安装 JDK 的 HOME 映射（新增 JDK 时在此追加）
declare -A _JDK_HOMES=(
  [8]="/Library/Java/JavaVirtualMachines/jdk1.8.0_271.jdk/Contents/Home"
  [17]="/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home"
  [21]="/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"
)

_jdk_switch() {
  local ver="${1:-}"

  # 无参数：展示当前环境与可用版本
  if [[ -z "$ver" ]]; then
    echo "当前 JAVA_HOME: ${JAVA_HOME:-<未设置>}"
    echo "当前 java: $(command -v java || echo '<未找到>')"
    echo ""
    echo "可用版本（可用 source scripts/jdk.sh <版本> 切换）:"
    for v in 8 17 21; do
      if [[ -d "${_JDK_HOMES[$v]}" ]]; then
        echo "  JDK $v -> ${_JDK_HOMES[$v]}"
      fi
    done
    return 0
  fi

  local home="${_JDK_HOMES[$ver]:-}"
  if [[ -z "$home" || ! -d "$home" ]]; then
    echo "错误：未安装 JDK $ver 或路径不存在，请检查 scripts/jdk.sh 中的映射。" >&2
    return 1
  fi

  export JAVA_HOME="$home"
  export PATH="$JAVA_HOME/bin:$PATH"
  echo "已切换到 JDK $ver: $JAVA_HOME"
  java -version 2>&1
}

_jdk_switch "$@"
