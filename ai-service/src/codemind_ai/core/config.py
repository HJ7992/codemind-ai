from functools import lru_cache

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """只从服务端环境读取配置，密钥不参与健康检查响应。"""

    model_config = SettingsConfigDict(env_prefix="CODEMIND_", extra="ignore")

    qdrant_url: str = "http://qdrant:6333"
    qdrant_collection: str = "codemind_chunks"
    source_root: str = "/data/sources"
    internal_service_token: SecretStr | None = None
    chat_base_url: str = ""
    chat_model: str = ""
    chat_api_key: SecretStr | None = None
    embedding_base_url: str = ""
    embedding_model: str = ""
    embedding_api_key: SecretStr | None = None
    embedding_dimensions: int = 1536
    embedding_batch_size: int = 16
    embedding_timeout_seconds: float = 30.0
    qdrant_timeout_seconds: float = 10.0

    def configuration_is_valid(self) -> bool:
        required_text = (
            self.qdrant_url,
            self.qdrant_collection,
            self.source_root,
            self.chat_base_url,
            self.chat_model,
            self.embedding_base_url,
            self.embedding_model,
        )
        urls_valid = all(
            value.startswith(("http://", "https://"))
            for value in (self.qdrant_url, self.chat_base_url, self.embedding_base_url)
        )
        return (
            all(value.strip() for value in required_text)
            and urls_valid
            and self.chat_api_key is not None
            and bool(self.chat_api_key.get_secret_value())
            and self.embedding_api_key is not None
            and bool(self.embedding_api_key.get_secret_value())
            and self.internal_service_token is not None
            and bool(self.internal_service_token.get_secret_value())
            and self.embedding_dimensions > 0
            and self.embedding_batch_size > 0
            and self.embedding_timeout_seconds > 0
            and self.qdrant_timeout_seconds > 0
        )


@lru_cache
def get_settings() -> Settings:
    return Settings()
