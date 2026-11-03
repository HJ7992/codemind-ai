from collections.abc import Sequence

from codemind_ai.infrastructure.qdrant.base import StoredChunk


class FakeChatClient:
    def __init__(self, answer: str = "登录逻辑位于 [1]。") -> None:
        self.result = answer
        self.calls: list[tuple[str, list[StoredChunk]]] = []

    def answer(self, question: str, evidence: Sequence[StoredChunk]) -> str:
        self.calls.append((question, list(evidence)))
        return self.result
