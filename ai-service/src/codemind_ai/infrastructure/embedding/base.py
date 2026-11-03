from collections.abc import Sequence
from typing import Protocol


class EmbeddingError(RuntimeError):
    """Embedding 外发或响应不符合契约时抛出的安全错误。"""


class EmbeddingClient(Protocol):
    def embed(self, texts: Sequence[str]) -> list[list[float]]: ...
