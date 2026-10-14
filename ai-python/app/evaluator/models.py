"""评测中心数据模型与数据集装载（M3-3.10）。

- :class:`GoldenCase`：一条黄金用例（问题 + 判定期望）。
- :class:`CaseResult` / :class:`EvalReport`：单例结果与整体报告（可直接序列化为 JSON）。
- :func:`load_dataset`：从 JSON 装载 Golden Set（缺字段/非法结构直接报错，避免评测跑偏）。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

# 内置 Golden Set 与种子知识库目录（打包随应用分发）
GOLDEN_DIR = Path(__file__).resolve().parent / "golden"
DEFAULT_DATASET_PATH = GOLDEN_DIR / "cases.json"
DEFAULT_KB_DIR = GOLDEN_DIR / "kb"


@dataclass(slots=True)
class Expectation:
    """用例判定期望。

    - ``must_contain``：必须全部出现的关键事实（AND）。
    - ``any_of``：至少出现其一的候选表达（OR，容忍同义改写）。
    - ``must_not_contain``：不得出现的编造信息（幻觉守卫）。
    - ``expect_heading``：期望命中的知识标题（作为检索命中指标，不参与通过判定）。
    - ``refusal``：知识不足时应如实拒答（不编造）。
    判定前统一做「去空白 + 小写」归一化，降低排版差异带来的误判。
    """

    must_contain: list[str] = field(default_factory=list)
    any_of: list[str] = field(default_factory=list)
    must_not_contain: list[str] = field(default_factory=list)
    expect_heading: str = ""
    refusal: bool = False


@dataclass(slots=True)
class GoldenCase:
    """一条黄金用例。"""

    id: str
    query: str
    tags: list[str] = field(default_factory=list)
    expect: Expectation = field(default_factory=Expectation)


@dataclass(slots=True)
class CaseResult:
    """单条用例的评测结果。"""

    case_id: str
    query: str
    tags: list[str]
    passed: bool
    answer: str
    references: list[str]
    missing: list[str]  # 未出现的关键事实
    forbidden: list[str]  # 出现的幻觉关键词
    refusal_expected: bool
    refusal_observed: bool
    heading_hit: bool | None  # None 表示该用例未约定期望标题
    latency_ms: float
    reasons: list[str]

    def to_dict(self) -> dict:
        return {
            "case_id": self.case_id,
            "query": self.query,
            "tags": self.tags,
            "passed": self.passed,
            "reasons": self.reasons,
            "missing": self.missing,
            "forbidden": self.forbidden,
            "refusal_expected": self.refusal_expected,
            "refusal_observed": self.refusal_observed,
            "heading_hit": self.heading_hit,
            "latency_ms": round(self.latency_ms, 1),
            "references": self.references,
            "answer": self.answer,
        }


@dataclass(slots=True)
class EvalReport:
    """Golden Set 回归报告。"""

    generated_at: str
    dataset_path: str
    total: int
    passed: int
    pass_rate: float
    refusal_total: int
    refusal_passed: int
    retrieval_total: int
    retrieval_hit: int
    hallucination_cases: int
    avg_latency_ms: float
    p95_latency_ms: float
    results: list[CaseResult]
    badcases: list[CaseResult]

    def summary(self) -> dict:
        """精简摘要（供日志、REST 与 CI 输出）。"""
        return {
            "generated_at": self.generated_at,
            "dataset_path": self.dataset_path,
            "total": self.total,
            "passed": self.passed,
            "pass_rate": round(self.pass_rate, 4),
            "refusal_accuracy": round(self.refusal_passed / self.refusal_total, 4)
            if self.refusal_total
            else None,
            "retrieval_hit_rate": round(self.retrieval_hit / self.retrieval_total, 4)
            if self.retrieval_total
            else None,
            "hallucination_cases": self.hallucination_cases,
            "avg_latency_ms": round(self.avg_latency_ms, 1),
            "p95_latency_ms": round(self.p95_latency_ms, 1),
            "badcases": [result.case_id for result in self.badcases],
        }

    def to_dict(self) -> dict:
        return {**self.summary(), "results": [result.to_dict() for result in self.results]}


def load_dataset(path: str | Path | None = None) -> tuple[str, list[GoldenCase]]:
    """装载 Golden Set。

    Args:
        path: 数据集路径；``None`` 使用内置数据集。

    Returns:
        ``(数据集描述, 用例列表)``。

    Raises:
        ValueError: 文件缺失、JSON 非法或用例结构不完整（缺 id/query）。
    """
    dataset_path = Path(path) if path else DEFAULT_DATASET_PATH
    if not dataset_path.exists():
        raise ValueError(f"Golden Set 不存在: {dataset_path}")
    try:
        raw = json.loads(dataset_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ValueError(f"Golden Set JSON 解析失败: {exc}") from exc

    items = raw.get("cases")
    if not isinstance(items, list) or not items:
        raise ValueError("Golden Set 缺少非空 cases 列表")

    cases: list[GoldenCase] = []
    for index, item in enumerate(items):
        case_id = str(item.get("id", "")).strip()
        query = str(item.get("query", "")).strip()
        if not case_id or not query:
            raise ValueError(f"第 {index + 1} 条用例缺少 id 或 query")
        expect_raw = item.get("expect") or {}
        cases.append(
            GoldenCase(
                id=case_id,
                query=query,
                tags=[str(tag) for tag in item.get("tags") or []],
                expect=Expectation(
                    must_contain=[str(x) for x in expect_raw.get("must_contain") or []],
                    any_of=[str(x) for x in expect_raw.get("any_of") or []],
                    must_not_contain=[str(x) for x in expect_raw.get("must_not_contain") or []],
                    expect_heading=str(expect_raw.get("expect_heading", "") or ""),
                    refusal=bool(expect_raw.get("refusal", False)),
                ),
            )
        )
    return str(raw.get("description", "")), cases
