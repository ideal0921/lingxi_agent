"""应用配置：全部环境变量化，前缀 LINGXI_，无硬编码密钥。"""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """运行配置，支持 .env 文件与系统环境变量。"""

    model_config = SettingsConfigDict(env_prefix="LINGXI_", env_file=".env", extra="ignore")

    # 服务信息
    app_name: str = "ai-python"

    # LLM 网关（DeepSeek 为 M1 主模型，其余模型 M3 接入）
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-chat"

    # 调用约束：超时 / 重试 / 降级
    llm_timeout: float = 30.0
    llm_max_retries: int = 2
    llm_retry_backoff_base: float = 0.5


settings = Settings()
