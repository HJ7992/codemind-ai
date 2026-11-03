from collections.abc import Sequence
from dataclasses import dataclass
from typing import Protocol

from codemind_ai.domain.source import CodeChunk


@dataclass(frozen=True, slots=True)
class StoredChunk:
    chunk_id: str
    file_path: str
    start_line: int
    end_line: int
    snippet: str
    score: float


class VectorStoreError(RuntimeError):
    """Qdrant 不可用或写入不完整时抛出的安全错误。"""


class VectorStore(Protocol):
    def ensure_collection(self, vector_size: int) -> None: ...

    def delete_version(self, project_id: str, source_version_id: str) -> None: ...

    def upsert(self, chunks: Sequence[CodeChunk], vectors: Sequence[Sequence[float]]) -> None: ...

    def count_version(self, project_id: str, source_version_id: str) -> int: ...

    def search(
        self,
        project_id: str,
        source_version_id: str,
        query_vector: Sequence[float],
        limit: int,
    ) -> list[StoredChunk]: ...
