"""DeepSeek Provider：OpenAI 兼容协议接入（降级链主模型）。"""

from app.config import settings
from app.gateway.providers.openai_compat import _STREAM_END, OpenAICompatibleProvider

# 兼容既有引用（测试与外部按模块名导入 SSE 哨兵）
__all__ = ["_STREAM_END", "DeepSeekProvider"]


class DeepSeekProvider(OpenAICompatibleProvider):
    """DeepSeek 接入（M1 主模型，降级链首位）。"""

    def __init__(self, config=settings) -> None:
        super().__init__(
            name="deepseek",
            api_key=config.deepseek_api_key,
            base_url=config.deepseek_base_url,
            model=config.deepseek_model,
        )
