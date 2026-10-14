"""通义千问 Provider：阿里云 DashScope OpenAI 兼容模式接入（降级链备用）。"""

from app.config import settings
from app.gateway.providers.openai_compat import OpenAICompatibleProvider


class QwenProvider(OpenAICompatibleProvider):
    """通义千问接入（qwen-plus / qwen-max，OpenAI 兼容模式）。"""

    def __init__(self, config=settings) -> None:
        super().__init__(
            name="qwen",
            api_key=config.qwen_api_key,
            base_url=config.qwen_base_url,
            model=config.qwen_model,
        )
