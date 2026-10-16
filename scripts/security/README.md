# M5-5.7 安全加固：扫描脚本

本目录提供两个可重复执行的脚本，用于依赖漏洞扫描与接口渗透测试。均**不改动任何业务代码**。

## 1. dependency-scan.sh — 依赖漏洞扫描
- 用途：逐生态（Node / Python / Go / Java）扫描依赖漏洞，末尾打印汇总表。
- 依赖：`npm`、`go`、`mvn`、`ai-python/.venv/bin/python`（或系统 `python3`）、`perl`（超时用）。
  未安装 trivy/grype/k6/jq，故全部用各生态原生工具 + python3 解析 JSON。
- 用法：`bash scripts/security/dependency-scan.sh`
- 结果：完整证据写入 `reports/dependency-scan-<YYYYMMDD-HHMM>.log`；终端打印汇总表。
- 退出码：`0` = 全部 PASS/SKIPPED；`1` = 存在 FAIL（发现高危漏洞）。
- 单项失败（联网失败/工具缺失）记为 SKIPPED，**不中断**其余扫描。

## 2. pentest.py — 接口渗透测试
- 用途：对**正在运行**的服务发真实 HTTP 请求，逐条断言认证/鉴权/越权/注入/错误处理/限流/上传。
- 依赖：仅 python3 标准库（urllib + threading），无需第三方包。
- 用法：`python3 scripts/security/pentest.py`
- 可覆盖环境变量：`GATEWAY_BASE`、`ADMIN_USER`、`ADMIN_PASSWORD`、`TENANT`、`AGENT_USER`、`AGENT_PASSWORD`
  （默认值即当前联调环境；请按需在本地或 CI 中覆盖，勿把密钥写进代码）。
- 结果：每条用例一行 `[PASS]/[FAIL] 名称 - 证据`，末尾 `合计: 通过 X / 失败 Y`；
  报告同时写入 `reports/pentest-<YYYYMMDD-HHMM>.log`。
- 退出码：`0` = 全部通过；`1` = 存在 FAIL；准备步骤（登录）失败会明确报错退出。

## 说明
- 服务未启动时 pentest.py 的登录步骤会失败并退出，请先启动网关（`:8080`）与 Java（`:8081`）。
- 私有 npm registry 若不支持 audit 接口，dependency-scan 会如实记为 SKIPPED。
