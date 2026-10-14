"""评测编排（M3-3.10）：种子知识入库 → 逐用例执行 Agent → 规则判定 → 报告与 Badcase 落盘。

链路::

    种子知识文档 ──ingest──▶ 评测专用租户/知识库（Milvus，与业务数据隔离）
    Golden Set 用例 ──▶ Agent 图（检索 + 生成）──▶ 规则判定 ──▶ 报告 JSON + Badcase JSONL

要点：
- **可重复执行**：种子文档按 ``doc_id`` 全量替换，重复跑评测结果稳定；
- **顺序执行**：逐条串行以避免触发云端限流，延迟指标因此也更具可比性；
- **Badcase 回流**：未通过用例追加写入 ``badcase.jsonl``（累积），便于后续人工分析与补数据。
"""

from __future__ import annotations

import json
import logging
import math
import tempfile
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from app.agent.graph import build_graph
from app.config import Settings
from app.evaluator.judge import judge
from app.evaluator.models import (
    DEFAULT_KB_DIR,
    CaseResult,
    EvalReport,
    load_dataset,
)
from app.rag.ingest.pipeline import ingest_document

logger = logging.getLogger(__name__)

# 进程内最近一次评测状态（供 REST 查询；进程重启即清空，报告文件仍保留）
_last_report: EvalReport | None = None
_last_error: str = ""
_running: bool = False


def resolve_report_dir(config: Settings) -> Path:
    """报告目录：优先配置项，缺省落到系统临时目录（避免污染工程目录）。"""
    base = config.eval_report_dir or str(Path(tempfile.gettempdir()) / "lingxi-eval")
    path = Path(base)
    path.mkdir(parents=True, exist_ok=True)
    return path


def seed_documents(store: Any, embedder: Any, config: Settings, kb_dir: Path = DEFAULT_KB_DIR) -> list[str]:
    """把内置种子知识文档入库到评测专用租户/知识库（按 doc_id 全量替换，可重复执行）。"""
    doc_ids: list[str] = []
    for path in sorted(Path(kb_dir).glob("*.md")):
        doc_id = f"eval-{path.stem}"
        result = ingest_document(
            path,
            doc_id=doc_id,
            tenant_id=config.eval_seed_tenant_id,
            kb_id=config.eval_seed_kb_id,
            embedder=embedder,
            store=store,
        )
        logger.info("种子文档入库 docId=%s 子块=%d", doc_id, result.child_count)
        doc_ids.append(doc_id)
    return doc_ids


def cleanup_documents(store: Any, config: Settings, doc_ids: list[str]) -> None:
    """清理评测种子切片（评测后释放向量库占用）。"""
    for doc_id in doc_ids:
        store.drop_document(doc_id, config.eval_seed_tenant_id)


async def run_golden_set(
    *,
    retriever: Any,
    gateway: Any,
    store: Any,
    embedder: Any,
    config: Settings,
    dataset_path: str | Path | None = None,
    report_dir: Path | None = None,
    seed: bool = True,
    cleanup: bool = False,
) -> EvalReport:
    """执行一次 Golden Set 回归并返回报告（同时落盘）。

    Args:
        retriever: 检索器（生产为 runtime 单例）。
        gateway: LLM 网关。
        store: Milvus 存储（种子入库与清理）。
        embedder: 向量化器（种子入库）。
        config: 应用配置（评测租户/知识库、数据集与报告目录）。
        dataset_path: 数据集路径；缺省用配置项或内置数据集。
        report_dir: 报告目录；缺省用配置项或系统临时目录。
        seed: 是否先把种子知识文档入库。
        cleanup: 评测结束后是否清理种子切片（默认保留，便于复跑与排查）。
    """
    effective_dataset = dataset_path or config.eval_dataset_path or None
    description, cases = load_dataset(effective_dataset)
    logger.info("Golden Set 装载完成：用例 %d 条（%s）", len(cases), description[:60])

    doc_ids: list[str] = []
    if seed:
        doc_ids = seed_documents(store, embedder, config)

    graph = build_graph(retriever=retriever, gateway=gateway)
    results: list[CaseResult] = []
    for case in cases:
        started = time.perf_counter()
        answer = ""
        references: list[str] = []
        try:
            state = await graph.ainvoke(
                {
                    "query": case.query,
                    "tenant_id": config.eval_seed_tenant_id,
                    "kb_id": config.eval_seed_kb_id,
                }
            )
            answer = state.get("answer", "") or ""
            references = [hit.heading_path or "" for hit in state.get("contexts") or []]
        except Exception as exc:  # 单条失败不中断整轮评测
            logger.exception("用例执行失败 caseId=%s", case.id)
            answer = f"<评测执行失败：{exc}>"
        latency_ms = (time.perf_counter() - started) * 1000

        outcome = judge(case, answer, references)
        results.append(
            CaseResult(
                case_id=case.id,
                query=case.query,
                tags=case.tags,
                passed=outcome.passed,
                answer=answer,
                references=references,
                missing=outcome.missing,
                forbidden=outcome.forbidden,
                refusal_expected=case.expect.refusal,
                refusal_observed=outcome.refusal_observed,
                heading_hit=outcome.heading_hit,
                latency_ms=latency_ms,
                reasons=outcome.reasons,
            )
        )

    report = _build_report(results, dataset_path=str(effective_dataset or ""))
    _persist(report, report_dir or resolve_report_dir(config))

    if cleanup:
        cleanup_documents(store, config, doc_ids)
    return report


def _build_report(results: list[CaseResult], *, dataset_path: str) -> EvalReport:
    """汇总指标并组装报告。"""
    total = len(results)
    passed = sum(1 for result in results if result.passed)
    latencies = [result.latency_ms for result in results]
    refusal_results = [result for result in results if result.refusal_expected]
    heading_results = [result for result in results if result.heading_hit is not None]

    return EvalReport(
        generated_at=datetime.now(UTC).isoformat(timespec="seconds"),
        dataset_path=dataset_path,
        total=total,
        passed=passed,
        pass_rate=passed / total if total else 0.0,
        refusal_total=len(refusal_results),
        refusal_passed=sum(1 for result in refusal_results if result.passed),
        retrieval_total=len(heading_results),
        retrieval_hit=sum(1 for result in heading_results if result.heading_hit),
        hallucination_cases=sum(1 for result in results if result.forbidden),
        avg_latency_ms=sum(latencies) / total if total else 0.0,
        p95_latency_ms=_percentile(latencies, 95),
        results=results,
        badcases=[result for result in results if not result.passed],
    )


def _percentile(values: list[float], percent: float) -> float:
    """就近取位的分位数（样本量小，无需插值）。"""
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(percent / 100 * len(ordered)) - 1))
    return ordered[index]


def _persist(report: EvalReport, out_dir: Path) -> tuple[Path, Path]:
    """报告与 Badcase 落盘：报告另存 latest 便于查询；Badcase 累积追加便于回流。"""
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
    payload = json.dumps(report.to_dict(), ensure_ascii=False, indent=2)
    report_path = out_dir / f"report-{stamp}.json"
    report_path.write_text(payload, encoding="utf-8")
    (out_dir / "latest.json").write_text(payload, encoding="utf-8")

    badcase_path = out_dir / "badcase.jsonl"
    with badcase_path.open("a", encoding="utf-8") as handle:
        for badcase in report.badcases:
            record = {"generated_at": report.generated_at, **badcase.to_dict()}
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
    logger.info(
        "评测报告已落盘：%s（通过 %d/%d，Badcase %d）",
        report_path,
        report.passed,
        report.total,
        len(report.badcases),
    )
    return report_path, badcase_path


def is_running() -> bool:
    """当前是否有评测在执行（REST 触发用，避免并发重复跑）。"""
    return _running


def latest_summary() -> dict:
    """最近一次评测摘要（进程内）；未跑过或失败时返回状态说明。"""
    if _last_report is not None:
        return {"status": "ok", **(_last_report.summary())}
    if _last_error:
        return {"status": "failed", "error": _last_error}
    return {"status": "empty", "message": "尚未执行过评测"}


async def launch_eval(*, retriever: Any, gateway: Any, store: Any, embedder: Any, config: Settings) -> bool:
    """后台触发一次评测（供 REST 异步任务调用）。

    Returns:
        ``True`` 表示已启动；``False`` 表示已有评测在执行（本次忽略）。
    """
    global _running, _last_report, _last_error
    if _running:
        return False
    _running = True
    _last_error = ""
    try:
        _last_report = await run_golden_set(
            retriever=retriever, gateway=gateway, store=store, embedder=embedder, config=config
        )
    except Exception as exc:
        _last_error = str(exc)
        logger.exception("Golden Set 评测失败")
        raise
    finally:
        _running = False
    return True
