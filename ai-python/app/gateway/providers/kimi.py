"""Kimi Provider：Moonshot OpenAI 兼容协议接入（降级链末位兜底）。"""

from app.config import settings
from app.gateway.providers.openai_compat import OpenAICompatibleProvider


class KimiProvider(OpenAICompatibleProvider):
    """Kimi（Moonshot）接入（OpenAI 兼容协议）。"""

    def __init__(self, config=settings) -> None:
        super().__init__(
            name="kimi",
            api_key=config.kimi_api_key,
            base_url=config.kimi_base_url,
            model=config.kimi_model,
        )
