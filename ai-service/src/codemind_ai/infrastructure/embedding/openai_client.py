from collections.abc import Sequence

import httpx

from codemind_ai.infrastructure.embedding.base import EmbeddingError


class OpenAIEmbeddingClient:
    """调用 OpenAI 兼容 Embedding API，只发送代码块文本。"""

    def __init__(
        self,
        base_url: str,
        model: str,
        api_key: str,
        dimensions: int,
        batch_size: int = 16,
        timeout_seconds: float = 30.0,
        client: httpx.Client | None = None,
    ) -> None:
        if dimensions <= 0 or batch_size <= 0:
            raise ValueError("Embedding 维度和批量大小必须大于 0")
        self.endpoint = f"{base_url.rstrip('/')}/embeddings"
        self.model = model
        self.api_key = api_key
        self.dimensions = dimensions
        self.batch_size = batch_size
        self.timeout_seconds = timeout_seconds
        self.client = client

    def embed(self, texts: Sequence[str]) -> list[list[float]]:
        if not texts:
            return []
        vectors: list[list[float]] = []
        for offset in range(0, len(texts), self.batch_size):
            vectors.extend(self._embed_batch(list(texts[offset : offset + self.batch_size])))
        return vectors

    def _embed_batch(self, texts: list[str]) -> list[list[float]]:
        try:
            if self.client is not None:
                response = self.client.post(
                    self.endpoint,
                    headers={"Authorization": f"Bearer {self.api_key}"},
                    json={"model": self.model, "input": texts},
                    timeout=self.timeout_seconds,
                )
            else:
                with httpx.Client(timeout=self.timeout_seconds) as client:
                    response = client.post(
                        self.endpoint,
                        headers={"Authorization": f"Bearer {self.api_key}"},
                        json={"model": self.model, "input": texts},
                    )
        except httpx.HTTPError as exception:
            raise EmbeddingError("向量生成服务暂时不可用") from exception
        if response.status_code != 200:
            raise EmbeddingError("向量生成服务返回错误")
        try:
            payload = response.json()
            items = sorted(payload["data"], key=lambda item: item["index"])
            vectors = [item["embedding"] for item in items]
        except (KeyError, TypeError, ValueError) as exception:
            raise EmbeddingError("向量生成服务响应格式无效") from exception
        if len(vectors) != len(texts) or any(
            not isinstance(vector, list) or len(vector) != self.dimensions for vector in vectors
        ):
            raise EmbeddingError("向量数量或维度与配置不一致")
        return vectors
