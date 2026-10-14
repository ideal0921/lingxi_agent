"""PII 脱敏单测（M3-3.9，离线运行，不触网）。

覆盖：各类型识别与误报防护、嵌套/重叠取舍、占位符冲突、跨字段一致性、
答案还原（含流式按占位符边界缓冲）、Agent 图「进模型前脱敏 / 出模型后还原」。
"""

from __future__ import annotations

from collections.abc import AsyncIterator

from app.agent.graph import build_graph, stream_answer
from app.agent.state import ChatMessage, StreamKind
from app.privacy import PiiKind, PiiMasker
from app.rag.retrieve import RetrievalHit

PHONE = "13812345678"
ID_CARD = "110105199003072316"
BANK_CARD = "6222021234567890123"
EMAIL = "user.name+tag@example.com"
ADDRESS = "收货地址是上海市浦东新区张江路100号"


def _hit(parent_content: str) -> RetrievalHit:
    return RetrievalHit(
        chunk_id="c1",
        doc_id="d1",
        kb_id="kb1",
        parent_id="p1",
        content="片段",
        seq=1,
        heading_path="售后政策",
        doc_type="markdown",
        page=None,
        score=0.9,
        parent_content=parent_content,
    )


class TestMasking:
    def test_masks_strong_patterns(self) -> None:
        masker = PiiMasker()
        masked = masker.mask(
            f"手机 {PHONE}，身份证 {ID_CARD}，卡号 {BANK_CARD}，邮箱 {EMAIL}"
        )

        assert PHONE not in masked
        assert ID_CARD not in masked
        assert BANK_CARD not in masked
        assert EMAIL not in masked
        assert "[手机号1]" in masked and "[身份证1]" in masked
        assert "[银行卡1]" in masked and "[邮箱1]" in masked

    def test_masks_address_heuristic(self) -> None:
        masker = PiiMasker()
        masked = masker.mask(ADDRESS)

        assert "[地址1]" in masked
        assert "张江路100号" not in masked

    def test_address_can_be_disabled(self) -> None:
        masker = PiiMasker(mask_address=False)
        assert masker.mask(ADDRESS) == ADDRESS

    def test_non_pii_business_text_is_untouched(self) -> None:
        masker = PiiMasker()
        text = "订单号 编号 已生成，退款时效为 1-3 个工作日，解决问题的思路是转人工"
        assert masker.mask(text) == text

    def test_disabled_returns_text_unchanged(self) -> None:
        masker = PiiMasker(enabled=False)
        assert masker.mask(f"电话 {PHONE}") == f"电话 {PHONE}"

    def test_round_trip_restores_original(self) -> None:
        masker = PiiMasker()
        text = f"联系 {PHONE}，证件 {ID_CARD}，{ADDRESS}"
        assert masker.restore(masker.mask(text)) == text


class TestNestedAndOverlap:
    def test_address_hides_embedded_phone_as_one_token(self) -> None:
        """地址内含手机号：整体替换为地址占位符，模型看不到其中的号码片段。"""
        masker = PiiMasker()
        masked = masker.mask(f"我的地址是北京市朝阳区望京街道100号，电话{PHONE}")

        assert "100号" not in masked
        assert "[地址1]" in masked
        # 手机号位于地址之外，应独立占位
        assert "[手机号1]" in masked
        assert PHONE not in masked

    def test_id_card_beats_bank_card_on_same_digits(self) -> None:
        """18 位身份证同时匹配银行卡规则时，按最长+优先级取身份证。"""
        masker = PiiMasker()
        masked = masker.mask(f"证件号 {ID_CARD}")

        assert "[身份证1]" in masked
        assert "[银行卡1]" not in masked


class TestPlaceholderCollision:
    def test_avoids_collision_with_literal_placeholder(self) -> None:
        """原文已含形如占位符的文本时，真实 PII 改用下一个序号，还原仍精确。"""
        masker = PiiMasker()
        text = f"客服说引用 [手机号1] 即可，我的号码是 {PHONE}"

        masked = masker.mask(text)

        assert "[手机号1]" in masked  # 用户原文中的字面占位符保留
        assert "[手机号2]" in masked  # 真实号码改用未冲突序号
        assert masker.restore(masked) == text

    def test_same_value_reuses_same_token_across_fields(self) -> None:
        masker = PiiMasker()
        query = masker.mask(f"我的电话是 {PHONE}")
        history = masker.mask(f"上次登记的电话也是 {PHONE}")

        assert "[手机号1]" in query
        assert "[手机号1]" in history  # 同一原文复用同一占位符，模型可关联
        assert len(masker.token_map) == 1

    def test_different_values_get_distinct_tokens(self) -> None:
        masker = PiiMasker()
        masked = masker.mask("甲 13800000001 乙 13800000002")

        assert "[手机号1]" in masked and "[手机号2]" in masked


class TestStreamRestore:
    async def _feed(self, masker: PiiMasker, text: str, chunk_size: int) -> str:
        async def _stream() -> AsyncIterator[str]:
            for start in range(0, len(text), chunk_size):
                yield text[start : start + chunk_size]

        return "".join([part async for part in masker.restore_stream(_stream())])

    async def test_restores_within_tainted_stream(self) -> None:
        """占位符被切成多段也能正确还原（缓冲到占位符闭合）。"""
        masker = PiiMasker()
        masker.mask(f"登记 {PHONE}")

        for size in (1, 2, 3, 5, 100):
            restored = await self._feed(masker, "您的[手机号1]已记录", size)
            assert restored == f"您的{PHONE}已记录", f"chunk_size={size}"

    async def test_passthrough_when_no_tokens(self) -> None:
        masker = PiiMasker()
        assert await self._feed(masker, "没有占位符的文本", 2) == "没有占位符的文本"

    async def test_no_partial_placeholder_in_emitted_chunks(self) -> None:
        """逐字符流出时，已输出的片段不得以未闭合占位符碎片结尾（否则前端会闪半截标记）。"""
        masker = PiiMasker()
        masker.mask(f"登记 {PHONE}")

        async def _stream() -> AsyncIterator[str]:
            for char in "您好[手机号1]，已记录":
                yield char

        emitted = ""
        async for part in masker.restore_stream(_stream()):
            emitted += part
            # 已输出内容中不允许出现「已开始但未闭合」的占位符
            assert emitted.count("[") == emitted.count("]"), f"片段切开占位符: {part!r}"
        assert emitted == f"您好{PHONE}，已记录"


class TestAgentGraphMasking:
    class _CapturingGateway:
        """记录送入模型的提示词，并按预设文本作答（模拟模型回显占位符）。"""

        def __init__(self, answer: str) -> None:
            self.answer = answer
            self.prompts: list[str] = []

        async def generate(self, prompt: str, model: str | None = None) -> str:
            self.prompts.append(prompt)
            return self.answer

        async def stream(self, prompt: str, model: str | None = None) -> AsyncIterator[str]:
            self.prompts.append(prompt)
            for char in self.answer:
                yield char

    class _CapturingRetriever:
        def __init__(self, hit: RetrievalHit) -> None:
            self._hit = hit
            self.queries: list[str] = []

        def retrieve(self, query, *, tenant_id, kb_id=None, top_k=None) -> list[RetrievalHit]:
            self.queries.append(query)
            return [self._hit]

    async def test_graph_masks_inputs_and_restores_answer(self) -> None:
        gateway = self._CapturingGateway("请核对 [手机号1] 与 [银行卡1] 是否正确")
        retriever = self._CapturingRetriever(_hit(f"持卡人银行卡 {BANK_CARD}"))
        graph = build_graph(retriever=retriever, gateway=gateway)

        state = await graph.ainvoke({"query": f"我的手机号 {PHONE} 对吗", "tenant_id": "t1"})

        prompt = gateway.prompts[0]
        assert PHONE not in prompt  # 手机号未进入模型
        assert BANK_CARD not in prompt  # 知识片段中的银行卡号同样未进入模型
        assert "[手机号1]" in prompt and "[银行卡1]" in prompt
        assert retriever.queries[0] == "我的手机号 [手机号1] 对吗"  # 检索亦用脱敏后问题

        # 回答中占位符还原为原文
        assert PHONE in state["answer"] and BANK_CARD in state["answer"]

    async def test_graph_masks_history(self) -> None:
        gateway = self._CapturingGateway("好的")
        graph = build_graph(gateway=gateway)

        await graph.ainvoke(
            {
                "query": "还有别的吗",
                "tenant_id": "t1",
                "history": [ChatMessage(role="user", content=f"上次留的电话是 {PHONE}")],
            }
        )

        assert PHONE not in gateway.prompts[0]
        assert "[手机号1]" in gateway.prompts[0]

    async def test_stream_answer_masks_and_restores(self) -> None:
        gateway = self._CapturingGateway("您的[手机号1]已登记")
        retriever = self._CapturingRetriever(_hit("无关内容"))

        events = [
            event
            async for event in stream_answer(
                f"登记手机号 {PHONE}", gateway=gateway, retriever=retriever, tenant_id="t1"
            )
        ]

        assert PHONE not in gateway.prompts[0]
        assert "[手机号1]" in gateway.prompts[0]
        text = "".join(event.delta for event in events if event.kind is StreamKind.DELTA)
        assert text == f"您的{PHONE}已登记"

    async def test_pii_kind_enum_exposed(self) -> None:
        assert {kind.value for kind in PiiKind} == {
            "phone",
            "id_card",
            "bank_card",
            "email",
            "address",
        }
