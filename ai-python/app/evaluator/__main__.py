"""评测中心 CLI：``python -m app.evaluator``。

示例::

    # 内置 Golden Set + 内置种子知识库（自动入库到评测专用租户）
    python -m app.evaluator

    # 自定义数据集 / 报告目录，并要求通过率不低于 0.9（CI 回归门禁）
    python -m app.evaluator --dataset ./my-cases.json --report-dir ./eval-out --min-pass-rate 0.9

退出码：0 达标；1 未达 ``--min-pass-rate``（可直接作为 CI 门禁）。
"""

from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path

from app.config import Settings
from app.evaluator.runner import resolve_report_dir, run_golden_set


def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="python -m app.evaluator", description="Golden Set 回归评测")
    parser.add_argument("--dataset", default="", help="Golden Set JSON 路径（缺省用内置数据集）")
    parser.add_argument("--report-dir", default="", help="报告目录（缺省用系统临时目录）")
    parser.add_argument("--no-seed", action="store_true", help="跳过种子知识入库（复用已入库数据）")
    parser.add_argument("--cleanup", action="store_true", help="评测后清理种子切片")
    parser.add_argument(
        "--min-pass-rate",
        type=float,
        default=0.8,
        help="通过率门禁；低于该值退出码为 1（默认 0.8）",
    )
    return parser.parse_args(argv)


async def _run(args: argparse.Namespace) -> int:
    # 延迟导入：避免 CLI 解析参数时即拉起 Milvus / 网关等重依赖
    from app.gateway.router import gateway
    from app.runtime import get_embedder, get_retriever, get_store

    config = Settings()
    report = await run_golden_set(
        retriever=await get_retriever(),
        gateway=gateway,
        store=await get_store(),
        embedder=await get_embedder(),
        config=config,
        dataset_path=args.dataset or None,
        report_dir=Path(args.report_dir) if args.report_dir else None,
        seed=not args.no_seed,
        cleanup=args.cleanup,
    )

    summary = report.summary()
    print("\n=== Golden Set 回归结果 ===")
    print(f"用例总数   : {summary['total']}")
    print(f"通过       : {summary['passed']}（通过率 {summary['pass_rate']:.1%}）")
    print(f"拒答准确率 : {summary['refusal_accuracy']}")
    print(f"检索命中率 : {summary['retrieval_hit_rate']}")
    print(f"幻觉用例数 : {summary['hallucination_cases']}")
    print(f"平均延迟   : {summary['avg_latency_ms']}ms（P95 {summary['p95_latency_ms']}ms）")
    print(f"报告目录   : {resolve_report_dir(config)}")

    if report.badcases:
        print("\n--- Badcase ---")
        for badcase in report.badcases:
            print(f"[{badcase.case_id}] {badcase.query}")
            print(f"    原因: {'；'.join(badcase.reasons)}")
            print(f"    回答: {badcase.answer[:120]}")

    if report.pass_rate < args.min_pass_rate:
        print(f"\n未达门禁：通过率 {report.pass_rate:.1%} < {args.min_pass_rate:.1%}")
        return 1
    return 0


def main(argv: list[str] | None = None) -> int:
    return asyncio.run(_run(_parse_args(argv)))


if __name__ == "__main__":
    sys.exit(main())
