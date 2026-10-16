"""灵犀智能平台 全链路基线压测（M5-5.2）。

## 定位与边界（务必如实理解，避免把结论用错）
- 本脚本在**单机**做小规模并发压测，目标是给出**可复现的性能基线与回归对比口径**
  （改代码后重跑，看 P95/TTFT 是否退化），**不作为「5000 并发」里程碑指标的证明**：
  分布式压测需要多机/K8s 集群与压测集群隔离，属未验证能力。
- 两个场景：
  1. `rest`：登录（真实鉴权链路）+ 受保护接口读（报表/日志），衡量网关 + Java 的吞吐与延迟；
  2. `chat`：建会话 → WSS（令牌走查询参数）→ 单轮问答，衡量**首字延迟 TTFT** 与整轮耗时
     （真实 LLM + Kafka + Milvus 检索链路，故耗时显著高于 REST 场景）。
- 输出：控制台汇总 + Markdown 报告（`scripts/bench/reports/bench-<时间戳>.md`），含 P50/P95/P99、
  成功率与错误分布，便于归档与对比。

用法：
    python scripts/bench/load-test.py --scenario both --users 24 --duration 15
    python scripts/bench/load-test.py --scenario chat --users 6 --rounds 1
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import statistics
import time
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

import httpx
import websockets

REPORT_DIR = Path(__file__).resolve().parent / "reports"


@dataclass
class Metrics:
    """单场景指标收集：延迟样本、成功/失败计数、错误分布。"""

    name: str
    latencies: list[float] = field(default_factory=list)
    ttfts: list[float] = field(default_factory=list)
    ok: int = 0
    failed: int = 0
    # rate_limited 单列统计：HTTP 429 是网关限流的**预期生效**，不等于链路故障，
    # 若与真实失败混算会把成功率误读为「链路不可用」。
    rate_limited: int = 0
    errors: Counter[str] = field(default_factory=Counter)

    def record_ok(self, latency_ms: float, ttft_ms: float | None = None) -> None:
        self.ok += 1
        self.latencies.append(latency_ms)
        if ttft_ms is not None:
            self.ttfts.append(ttft_ms)

    def record_fail(self, reason: str) -> None:
        self.failed += 1
        self.errors[reason] += 1

    def record_rate_limited(self, reason: str) -> None:
        """记录限流拒绝（429）：计入错误分布，但不计入「真实失败」。"""
        self.rate_limited += 1
        self.errors[reason] += 1

    @property
    def total(self) -> int:
        return self.ok + self.failed + self.rate_limited

    @property
    def success_rate(self) -> float:
        """含限流拒绝的整体成功率（反映压测端可见结果）。"""
        return self.ok / self.total if self.total else 0.0

    @property
    def effective_success_rate(self) -> float:
        """剔除限流拒绝后的成功率（反映「真正打进去的请求」的健康度）。"""
        denom = self.ok + self.failed
        return self.ok / denom if denom else 0.0

    def summary(self, elapsed_s: float) -> dict[str, float]:
        """汇总统计（空样本时返回 0，避免除零）。"""
        def pct(values: list[float], q: float) -> float:
            if not values:
                return 0.0
            ordered = sorted(values)
            index = min(int(len(ordered) * q), len(ordered) - 1)
            return round(ordered[index], 1)

        return {
            "请求数": self.total,
            "成功率": round(self.success_rate * 100, 2),
            "有效成功率": round(self.effective_success_rate * 100, 2),
            "限流(429)": self.rate_limited,
            "QPS": round(self.ok / elapsed_s, 1) if elapsed_s > 0 else 0.0,
            "P50(ms)": pct(self.latencies, 0.50),
            "P95(ms)": pct(self.latencies, 0.95),
            "P99(ms)": pct(self.latencies, 0.99),
            "平均(ms)": round(statistics.fmean(self.latencies), 1) if self.latencies else 0.0,
            "TTFT_P50(ms)": pct(self.ttfts, 0.50),
            "TTFT_P95(ms)": pct(self.ttfts, 0.95),
        }


async def login(client: httpx.AsyncClient, base: str, args: argparse.Namespace) -> str:
    """登录取得令牌（失败直接抛错：压测前的准备步骤不允许静默失败）。"""
    resp = await client.post(
        f"{base}/auth/login",
        json={"username": args.user, "password": args.password, "tenantId": args.tenant},
        timeout=15.0,
    )
    resp.raise_for_status()
    return resp.json()["data"]["token"]


async def rest_worker(args: argparse.Namespace, metrics: Metrics, deadline: float) -> None:
    """REST 场景：循环读受保护接口（令牌按需刷新，逼近真实后台使用行为）。"""
    base = f"{args.base_url}/api/v1"
    async with httpx.AsyncClient() as client:
        token = await login(client, base, args)
        headers = {"Authorization": f"Bearer {token}"}
        loops = 0
        while time.monotonic() < deadline:
            loops += 1
            # 令牌每 40 次循环刷新一次，避免把登录开销按满并发摊到每一次读
            if loops % 40 == 0:
                token = await login(client, base, args)
                headers = {"Authorization": f"Bearer {token}"}
            path = "/reports/csat" if loops % 2 else "/logs?size=3"
            start = time.monotonic()
            try:
                resp = await client.get(base + path, headers=headers, timeout=15.0)
                latency = (time.monotonic() - start) * 1000
                if resp.status_code == 200:
                    metrics.record_ok(latency)
                elif resp.status_code == 429:
                    metrics.record_rate_limited(f"HTTP {resp.status_code}")  # 限流预期生效，单列统计
                else:
                    metrics.record_fail(f"HTTP {resp.status_code}")
            except Exception as exc:  # noqa: BLE001 - 压测需归类所有异常，便于统计
                metrics.record_fail(type(exc).__name__)


async def chat_worker(worker_id: int, args: argparse.Namespace, metrics: Metrics) -> None:
    """问答场景：建会话 → WSS 连接（令牌走查询参数）→ 一轮问答，记录 TTFT 与总耗时。"""
    base = f"{args.base_url}/api/v1"
    async with httpx.AsyncClient() as client:
        token = await login(client, base, args)
        for round_no in range(args.rounds):
            round_start = time.monotonic()
            try:
                resp = await client.post(
                    f"{base}/session",
                    json={"user_id": f"bench-{worker_id}", "channel": "web"},
                    headers={"Authorization": f"Bearer {token}"},
                    timeout=15.0,
                )
                if resp.status_code != 200:
                    metrics.record_fail(f"建会话 HTTP {resp.status_code}")
                    continue
                session_id = resp.json()["session_id"]
                ws_base = args.base_url.replace("http://", "ws://").replace("https://", "wss://")
                first_chunk_at: float | None = None
                async with websockets.connect(
                    f"{ws_base}/ws?session_id={session_id}&token={token}",
                    open_timeout=15, close_timeout=5,
                ) as ws:
                    # TTFT 从「上行发送」时刻起算（不含建会话与握手，贴近首字延迟定义）；
                    # 整轮耗时仍从 round_start 计，二者差值即链路准备开销。
                    send_at = time.monotonic()
                    await ws.send(json.dumps({
                        "type": "send",
                        "content": f"退款多久能到账？（第 {round_no + 1} 轮）",
                        "sender": f"bench-{worker_id}",
                    }))
                    finished = False
                    deadline = time.monotonic() + args.chat_timeout
                    while not finished and time.monotonic() < deadline:
                        envelope = json.loads(await asyncio.wait_for(ws.recv(), timeout=args.chat_timeout))
                        if envelope.get("type") != "chunk":
                            continue
                        if first_chunk_at is None:
                            first_chunk_at = time.monotonic()
                        if envelope.get("finished"):
                            finished = True
                if not finished:
                    metrics.record_fail("问答超时未完成")
                    continue
                total_ms = (time.monotonic() - round_start) * 1000
                ttft_ms = (first_chunk_at - send_at) * 1000 if first_chunk_at else total_ms
                metrics.record_ok(total_ms, ttft_ms)
            except Exception as exc:  # noqa: BLE001 - 同上
                metrics.record_fail(type(exc).__name__)


async def run_scenario(name: str, args: argparse.Namespace) -> tuple[Metrics, float]:
    """按并发数拉起工作协程并统计总耗时。"""
    metrics = Metrics(name=name)
    started = time.monotonic()
    if name == "rest":
        deadline = started + args.duration
        await asyncio.gather(*(rest_worker(args, metrics, deadline) for _ in range(args.users)))
    else:
        await asyncio.gather(*(chat_worker(i, args, metrics) for i in range(args.users)))
    return metrics, time.monotonic() - started


def write_report(results: list[tuple[Metrics, float, dict[str, float]]], args: argparse.Namespace) -> Path:
    """输出 Markdown 报告（含环境与口径说明，便于归档对比）。"""
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    path = REPORT_DIR / f"bench-{stamp}.md"
    lines = [
        f"# 全链路基线压测报告（M5-5.2）",
        "",
        f"- 时间：{datetime.now().isoformat(timespec='seconds')}",
        f"- 目标：`{args.base_url}`（单机运行，非分布式压测）",
        f"- 账号：{args.user}@{args.tenant}",
        "",
        "> **口径说明**：本报告为**单机小规模基线**，用于回归对比；"
        "不构成「5000 并发」里程碑指标的证明（分布式压测需集群环境，未验证）。",
        "> 网关对**单 IP / 单租户各限流 100 QPS（burst 50）**：单机压测建议 REST 并发 ≤8；"
        "若出现较多 `HTTP 429`，属限流预期生效（非故障），需降并发或改多机多 IP 复测。",
        "> chat 场景的 `TTFT_*` 已剔除建会话/握手开销，从上行发送时刻起算；"
        "`P50/P95` 为整轮耗时（含链路准备 + 真实 LLM + 检索）。",
        "",
        "| 场景 | 请求数 | 成功率 | 有效成功率 | 限流(429) | QPS | P50(ms) | P95(ms) | P99(ms) | 平均(ms) | TTFT_P50(ms) | TTFT_P95(ms) |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for metrics, elapsed, stats in results:
        lines.append(
            f"| {metrics.name} | {stats['请求数']} | {stats['成功率']}% | {stats['有效成功率']}% | "
            f"{stats['限流(429)']} | {stats['QPS']} | "
            f"{stats['P50(ms)']} | {stats['P95(ms)']} | {stats['P99(ms)']} | {stats['平均(ms)']} | "
            f"{stats['TTFT_P50(ms)']} | {stats['TTFT_P95(ms)']} |"
        )
    lines += ["", "## 场景耗时与错误分布", ""]
    for metrics, elapsed, _ in results:
        lines.append(
            f"- **{metrics.name}**：总耗时 {round(elapsed, 1)}s，"
            f"真实失败 {metrics.failed} 次，限流拒绝(429) {metrics.rate_limited} 次"
        )
        for reason, count in metrics.errors.most_common():
            lines.append(f"  - {reason}: {count}")
    lines += [
        "",
        "## 结论口径",
        "",
        "- 验收线（首字延迟 < 800ms）在**单机基线**下的达成情况见 TTFT 指标；",
        "- 若 P95 明显高于基线，需结合网关访问日志与 Java/网关线程池配置排查；",
        "- 复现命令：`python scripts/bench/load-test.py --scenario both --users 8 --chat-users 6 --duration 20`",
    ]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="灵犀全链路基线压测（单机小规模）")
    parser.add_argument("--base-url", default=os.environ.get("BENCH_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--scenario", choices=("rest", "chat", "both"), default="both")
    parser.add_argument("--users", type=int, default=8, help="REST 场景并发虚拟用户数（建议 ≤8，避免触发网关 100 QPS 限流）")
    parser.add_argument("--chat-users", type=int, default=0, help="chat 场景并发用户数（缺省按 users//4，上限 8）")
    parser.add_argument("--duration", type=int, default=15, help="REST 场景持续时间（秒）")
    parser.add_argument("--rounds", type=int, default=1, help="chat 场景每用户问答轮数")
    parser.add_argument("--chat-timeout", type=int, default=60, help="单轮问答超时（秒）")
    parser.add_argument("--user", default=os.environ.get("BENCH_USER", "admin"))
    parser.add_argument("--password", default=os.environ.get("BENCH_PASSWORD", "lingxi@2026"))
    parser.add_argument("--tenant", default=os.environ.get("BENCH_TENANT", "lingxi"))
    return parser.parse_args()


async def main() -> None:
    args = parse_args()
    scenarios = ["rest", "chat"] if args.scenario == "both" else [args.scenario]
    results = []
    for name in scenarios:
        if name == "rest":
            users = args.users
        else:
            # chat 并发须显式指定更稳妥；未指定时按 users//4 推断并封顶 8，避免压垮真实 LLM 链路
            users = args.chat_users if args.chat_users > 0 else max(1, min(args.users // 4, 8))
        args_for_run = argparse.Namespace(**{**vars(args), "users": users})
        print(f"▶ 场景 {name}：并发 {users}"
              + (f"，持续 {args.duration}s" if name == "rest" else f"，每用户 {args.rounds} 轮"))
        metrics, elapsed = await run_scenario(name, args_for_run)
        stats = metrics.summary(elapsed)
        results.append((metrics, elapsed, stats))
        print("  " + " | ".join(f"{key}={value}" for key, value in stats.items()))
        for reason, count in metrics.errors.most_common(3):
            print(f"  错误 {reason}: {count}")
    report = write_report(results, args)
    print(f"\n报告已写入：{report}")


if __name__ == "__main__":
    asyncio.run(main())
