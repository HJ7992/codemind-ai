from collections.abc import Sequence
from typing import Protocol

from codemind_ai.infrastructure.qdrant.base import StoredChunk


class ChatError(RuntimeError):
    """Chat 外发失败时抛出的安全错误。"""


class ChatClient(Protocol):
    def answer(self, question: str, evidence: Sequence[StoredChunk]) -> str: ...
