"""评测中心单测（M3-3.10，离线运行，不触网 / 不连 Milvus）。

覆盖：Golden Set 数据一致性、规则判定各维度（关键事实 / 候选表达 / 幻觉守卫 / 拒答）、
评测编排的报告与 Badcase 落盘、状态查询。
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.config import Settings
from app.evaluator.judge import is_refusal, judge, normalize
from app.evaluator.models import (
    DEFAULT_KB_DIR,
    Expectation,
    GoldenCase,
    load_dataset,
)
from app.evaluator.runner import is_running, latest_summary, run_golden_set


def _case(case_id: str, query: str, expect: Expectation) -> GoldenCase:
    return GoldenCase(id=case_id, query=query, tags=[], expect=expect)


class TestGoldenDataset:
    def test_builtin_dataset_has_enough_cases(self) -> None:
        description, cases = load_dataset()

        assert "Golden Set" in description
        assert len(cases) >= 20, "Golden Set 至少 20 条用例"
        assert len({case.id for case in cases}) == len(cases), "用例 id 必须唯一"
        assert all(case.query for case in cases)

    def test_builtin_dataset_covers_refusal_and_guard(self) -> None:
        _, cases = load_dataset()

        assert any(case.expect.refusal for case in cases), "需含拒答用例"
        assert any(case.expect.must_not_contain for case in cases), "需含幻觉守卫用例"

    def test_expected_headings_exist_in_seed_documents(self) -> None:
        """用例约定的期望标题必须真实存在于种子知识文档中，防止数据集与知识库漂移。"""
        headings = set()
        for doc in sorted(Path(DEFAULT_KB_DIR).glob("*.md")):
            for line in doc.read_text(encoding="utf-8").splitlines():
                if line.startswith("## "):
                    headings.add(line[3:].strip())

        _, cases = load_dataset()
        expected = {case.expect.expect_heading for case in cases if case.expect.expect_heading}
        assert expected, "用例应约定期望标题"
        assert expected <= headings, f"未在种子文档中找到标题: {expected - headings}"

    def test_load_dataset_rejects_malformed(self, tmp_path: Path) -> None:
        bad = tmp_path / "bad.json"
        bad.write_text(json.dumps({"cases": [{"id": "x"}]}), encoding="utf-8")
        with pytest.raises(ValueError, match="缺少 id 或 query"):
            load_dataset(bad)

        missing = tmp_path / "missing.json"
        with pytest.raises(ValueError, match="不存在"):
            load_dataset(missing)

    def test_seed_documents_are_markdown(self) -> None:
        docs = list(Path(DEFAULT_KB_DIR).glob("*.md"))
        assert len(docs) >= 3


class TestJudge:
    def test_normalizes_whitespace_and_case(self) -> None:
        assert normalize(" 3-7 个工作日 ABC ") == "3-7个工作日abc"

    def test_passes_when_key_facts_present(self) -> None:
        outcome = judge(
            _case("c1", "退款多久", Expectation(must_contain=["3-7个工作日"])),
            "款项将于 3-7 个工作日内退回。",
            [],
        )
        assert outcome.passed
        assert outcome.missing == []

    def test_fails_on_missing_key_fact(self) -> None:
        outcome = judge(
            _case("c2", "退款多久", Expectation(must_contain=["3-7个工作日"])),
            "退款会尽快处理。",
            [],
        )
        assert not outcome.passed
        assert outcome.missing == ["3-7个工作日"]

    def test_any_of_tolerates_synonyms(self) -> None:
        expectation = Expectation(any_of=["优惠券", "实物礼品"])
        assert judge(_case("c3", "兑换什么", expectation), "可兑换优惠券", []).passed
        assert not judge(_case("c4", "兑换什么", expectation), "可兑换积分", []).passed

    def test_hallucination_guard(self) -> None:
        outcome = judge(
            _case("c5", "能15天无理由吗", Expectation(must_not_contain=["15天无理由"])),
            "可以的，支持 15 天无理由退款。",
            [],
        )
        assert not outcome.passed
        assert outcome.forbidden == ["15天无理由"]

    def test_refusal_expected(self) -> None:
        refusal_case = _case("c6", "支持比特币吗", Expectation(refusal=True))

        passed = judge(refusal_case, "知识片段未提及相关支付方式，建议转人工核实。", [])
        assert passed.passed
        assert passed.refusal_observed

        failed = judge(refusal_case, "我们支持比特币支付。", [])
        assert not failed.passed
        assert "疑似编造" in "；".join(failed.reasons)

    def test_refuses_when_facts_were_available(self) -> None:
        outcome = judge(
            _case("c7", "退款多久", Expectation(must_contain=["3-7个工作日"])),
            "抱歉，我无法回答这个问题。",
            [],
        )
        assert not outcome.passed

    def test_escalation_suffix_does_not_count_as_refusal(self) -> None:
        """作答后附带「建议转人工」属正常收尾，不应被判为拒答。"""
        outcome = judge(
            _case("c7b", "退款多久", Expectation(must_contain=["3-7个工作日"])),
            "款项将于 3-7 个工作日内退回；若超过时限，建议转人工客服核实。",
            [],
        )
        assert outcome.passed
        assert outcome.refusal_observed  # 仍记录为观察到转人工措辞

    def test_negated_forbidden_keyword_is_not_violation(self) -> None:
        """模型纠正用户错误前提（「不是 15 天无理由」）不应被判为编造。"""
        outcome = judge(
            _case("c7c", "能15天无理由吗", Expectation(must_contain=["7天"], must_not_contain=["15天无理由"])),
            "退款不是 15 天无理由，商品签收后 7 天内才支持无理由退款。",
            [],
        )
        assert outcome.passed
        assert outcome.forbidden == []

    def test_genuine_hallucination_is_still_caught(self) -> None:
        outcome = judge(
            _case("c7d", "能15天无理由吗", Expectation(must_not_contain=["15天无理由"])),
            "可以的，支持 15 天无理由退款。",
            [],
        )
        assert not outcome.passed
        assert outcome.forbidden == ["15天无理由"]

    def test_doubtful_wording_is_not_violation(self) -> None:
        """模型以存疑措辞复述用户前提（「是否为」「未提及」「无法确认…这一说法」）不应被判为编造。"""
        cases = [
            "无法确认黄金会员是否为终身免费；知识片段未提及「终身免费」，仅说明 10000 分可升级。",
            "根据现有知识片段，无法确认“黄金会员是终身免费”这一说法。知识片段仅说明 10000 分可升级，未提及是否终身免费。",
        ]
        for answer in cases:
            outcome = judge(
                _case("c7e", "终身免费吗", Expectation(any_of=["10000"], must_not_contain=["终身免费"])),
                answer,
                [],
            )
            assert outcome.passed, f"误判为编造: {answer}"
            assert outcome.forbidden == []

    def test_negation_in_previous_sentence_does_not_excuse(self) -> None:
        """跨句的否定标记不应豁免编造（避免把真实幻觉放过）。"""
        outcome = judge(
            _case("c7f", "能15天无理由吗", Expectation(must_not_contain=["15天无理由"])),
            "知识库中没有相关说明。支持 15 天无理由退款。",
            [],
        )
        assert not outcome.passed
        assert outcome.forbidden == ["15天无理由"]

    def test_heading_hit_is_metric_only(self) -> None:
        """期望标题只作为检索指标，不影响通过判定（避免精排波动导致回归不稳定）。"""
        outcome = judge(
            _case("c8", "退款多久", Expectation(must_contain=["3-7个工作日"], expect_heading="退款时效")),
            "3-7 个工作日。",
            ["售后服务政策 > 配送时效"],
        )
        assert outcome.passed
        assert outcome.heading_hit is False

    def test_refusal_marker_detection(self) -> None:
        assert is_refusal("知识片段中没有相关信息，建议转人工。")
        assert is_refusal("知识片段中没有关于公司股价的信息，无法为您提供。建议转接人工客服。")
        assert not is_refusal("退款需要 3-7 个工作日。")


class TestRunner:
    """编排测试：全内存替身，不连 Milvus / 不触网。"""

    class _Gateway:
        """按问题返回预设回答的假网关。"""

        def __init__(self, mapping: dict[str, str], default: str) -> None:
            self._mapping = mapping
            self._default = default

        async def generate(self, prompt: str, model: str | None = None) -> str:
            for key, value in self._mapping.items():
                if key in prompt:
                    return value
            return self._default

    class _Retriever:
        def retrieve(self, query, *, tenant_id, kb_id=None, top_k=None):
            return []

    def _dataset(self, tmp_path: Path) -> Path:
        payload = {
            "version": 1,
            "description": "单测数据集",
            "cases": [
                {"id": "fact-ok", "query": "退款多久到账", "expect": {"must_contain": ["3-7个工作日"]}},
                {"id": "fact-miss", "query": "退款条件", "expect": {"must_contain": ["7天"]}},
                {"id": "refusal-ok", "query": "支持比特币吗", "expect": {"refusal": True}},
                {"id": "guard-bad", "query": "能15天无理由吗", "expect": {"must_not_contain": ["15天无理由"]}},
            ],
        }
        path = tmp_path / "cases.json"
        path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
        return path

    async def test_runs_and_persists_report_and_badcase(self, tmp_path: Path) -> None:
        gateway = self._Gateway(
            mapping={
                "退款多久到账": "将于 3-7 个工作日内到账。",
                "退款条件": "不支持。",
                "能15天无理由吗": "可以的，支持 15 天无理由退款。",  # 编造事实，触发幻觉守卫
            },
            default="知识片段未提及，建议转人工。",
        )
        out_dir = tmp_path / "out"

        report = await run_golden_set(
            retriever=self._Retriever(),
            gateway=gateway,
            store=None,  # seed=False 时不需要存储
            embedder=None,
            config=Settings(),
            dataset_path=self._dataset(tmp_path),
            report_dir=out_dir,
            seed=False,
        )

        assert report.total == 4
        assert report.passed == 2  # fact-ok + refusal-ok
        assert report.pass_rate == pytest.approx(0.5)
        assert {badcase.case_id for badcase in report.badcases} == {"fact-miss", "guard-bad"}
        assert report.hallucination_cases == 1  # guard-bad 命中 must_not_contain
        assert report.refusal_total == 1 and report.refusal_passed == 1

        assert (out_dir / "latest.json").exists()
        records = (out_dir / "badcase.jsonl").read_text(encoding="utf-8").strip().splitlines()
        assert len(records) == 2
        assert json.loads(records[0])["case_id"] in {"fact-miss", "guard-bad"}

    async def test_badcase_file_accumulates_across_runs(self, tmp_path: Path) -> None:
        gateway = self._Gateway(mapping={}, default="知识片段未提及，建议转人工。")
        out_dir = tmp_path / "out"

        for _ in range(2):
            await run_golden_set(
                retriever=self._Retriever(),
                gateway=gateway,
                store=None,
                embedder=None,
                config=Settings(),
                dataset_path=self._dataset(tmp_path),
                report_dir=out_dir,
                seed=False,
            )

        lines = (out_dir / "badcase.jsonl").read_text(encoding="utf-8").strip().splitlines()
        assert len(lines) == 4  # 每轮 2 条 Badcase，累计追加


class TestState:
    def test_initial_state(self) -> None:
        assert is_running() is False
        summary = latest_summary()
        assert summary["status"] in {"empty", "ok", "failed"}
