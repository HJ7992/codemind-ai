import hashlib
import random
from collections.abc import Sequence

from codemind_ai.domain.source import CodeChunk
from codemind_ai.infrastructure.qdrant.base import StoredChunk


class FakeEmbeddingClient:
    def __init__(self, dimensions: int = 4, fail_on_batch: int | None = None) -> None:
        self.dimensions = dimensions
        self.fail_on_batch = fail_on_batch
        self.batches: list[list[str]] = []

    def embed(self, texts: Sequence[str]) -> list[list[float]]:
        self.batches.append(list(texts))
        if self.fail_on_batch is not None and len(self.batches) == self.fail_on_batch:
            raise RuntimeError("模拟 Embedding 失败")
        return [self._vector(text) for text in texts]

    def _vector(self, text: str) -> list[float]:
        seed = int.from_bytes(hashlib.sha256(text.encode()).digest()[:8], "big")
        generator = random.Random(seed)
        return [generator.uniform(-1, 1) for _ in range(self.dimensions)]


class InMemoryVectorStore:
    def __init__(self) -> None:
        self.vector_size: int | None = None
        self.deleted_versions: list[tuple[str, str]] = []
        self.points: dict[str, tuple[list[float], dict[str, object]]] = {}

    def ensure_collection(self, vector_size: int) -> None:
        if self.vector_size is not None and self.vector_size != vector_size:
            raise RuntimeError("维度不一致")
        self.vector_size = vector_size

    def delete_version(self, project_id: str, source_version_id: str) -> None:
        self.deleted_versions.append((project_id, source_version_id))
        self.points = {
            point_id: value
            for point_id, value in self.points.items()
            if not (
                value[1]["project_id"] == project_id
                and value[1]["source_version_id"] == source_version_id
            )
        }

    def upsert(self, chunks: Sequence[CodeChunk], vectors: Sequence[Sequence[float]]) -> None:
        for chunk, vector in zip(chunks, vectors, strict=True):
            self.points[chunk.chunk_id] = (
                list(vector),
                {
                    "record_type": "code_chunk",
                    "project_id": chunk.project_id,
                    "source_version_id": chunk.source_version_id,
                    "chunk_id": chunk.chunk_id,
                    "relative_path": chunk.relative_path,
                    "start_line": chunk.start_line,
                    "end_line": chunk.end_line,
                    "language": chunk.language.value,
                    "parse_status": chunk.parse_status.value,
                    "text": chunk.text,
                    "content_sha256": chunk.content_sha256,
                },
            )

    def count_version(self, project_id: str, source_version_id: str) -> int:
        return sum(
            1
            for _, payload in self.points.values()
            if payload["project_id"] == project_id
            and payload["source_version_id"] == source_version_id
            and payload["record_type"] == "code_chunk"
        )

    def search(
        self,
        project_id: str,
        source_version_id: str,
        query_vector: Sequence[float],
        limit: int,
    ) -> list[StoredChunk]:
        candidates = [
            (point_id, vector, payload)
            for point_id, (vector, payload) in self.points.items()
            if payload["project_id"] == project_id
            and payload["source_version_id"] == source_version_id
            and payload["record_type"] == "code_chunk"
        ]
        scored = sorted(
            (
                StoredChunk(
                    chunk_id=point_id,
                    file_path=str(payload["relative_path"]),
                    start_line=int(str(payload["start_line"])),
                    end_line=int(str(payload["end_line"])),
                    snippet=str(payload["text"]),
                    score=sum(a * b for a, b in zip(vector, query_vector, strict=False)),
                )
                for point_id, vector, payload in candidates
            ),
            key=lambda chunk: chunk.score,
            reverse=True,
        )
        return scored[:limit]
