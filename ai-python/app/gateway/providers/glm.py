"""智谱 GLM Provider：OpenAI 兼容协议接入（降级链备用）。"""

from app.config import settings
from app.gateway.providers.openai_compat import OpenAICompatibleProvider


class GlmProvider(OpenAICompatibleProvider):
    """智谱 GLM-4 接入（OpenAI 兼容协议）。"""

    def __init__(self, config=settings) -> None:
        super().__init__(
            name="glm",
            api_key=config.glm_api_key,
            base_url=config.glm_base_url,
            model=config.glm_model,
        )
