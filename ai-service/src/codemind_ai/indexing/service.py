from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path

from codemind_ai.domain.source import CodeChunk, SourceFileMetadata
from codemind_ai.infrastructure.embedding.base import EmbeddingClient
from codemind_ai.infrastructure.qdrant.base import VectorStore
from codemind_ai.parsing.service import SourceParsingService


class IndexingError(RuntimeError):
    """索引无法完整建立时抛出的安全错误。"""


@dataclass(frozen=True, slots=True)
class IndexSummary:
    source_files: tuple[SourceFileMetadata, ...]
    chunk_count: int


class SourceIndexingService:
    def __init__(
        self,
        parsing_service: SourceParsingService,
        embedding_client: EmbeddingClient,
        vector_store: VectorStore,
        vector_size: int,
        embedding_batch_size: int = 16,
    ) -> None:
        if vector_size <= 0 or embedding_batch_size <= 0:
            raise ValueError("向量维度和 Embedding 批量大小必须大于 0")
        self.parsing_service = parsing_service
        self.embedding_client = embedding_client
        self.vector_store = vector_store
        self.vector_size = vector_size
        self.embedding_batch_size = embedding_batch_size

    def index(
        self,
        source_root: Path,
        project_id: str,
        source_version_id: str,
        relative_paths: Sequence[str],
    ) -> IndexSummary:
        if not relative_paths:
            raise IndexingError("索引请求没有源码文件")
        if len(set(relative_paths)) != len(relative_paths):
            raise IndexingError("索引请求包含重复源码路径")

        self.vector_store.ensure_collection(self.vector_size)
        self.vector_store.delete_version(project_id, source_version_id)
        source_files: list[SourceFileMetadata] = []
        batch: list[CodeChunk] = []
        chunk_count = 0
        try:
            for relative_path in relative_paths:
                result = self.parsing_service.parse_file(
                    source_root, relative_path, project_id, source_version_id
                )
                source_files.append(result.source_file)
                batch.extend(result.chunks)
                while len(batch) >= self.embedding_batch_size:
                    chunk_count += self._flush(batch[: self.embedding_batch_size])
                    batch = batch[self.embedding_batch_size :]
            if batch:
                chunk_count += self._flush(batch)
            if chunk_count == 0:
                raise IndexingError("源码中没有可索引的代码块")
            if self.vector_store.count_version(project_id, source_version_id) != chunk_count:
                raise IndexingError("Qdrant 点数与代码块数量不一致")
        except Exception:
            try:
                self.vector_store.delete_version(project_id, source_version_id)
            except Exception:
                pass
            raise
        return IndexSummary(source_files=tuple(source_files), chunk_count=chunk_count)

    def _flush(self, chunks: list[CodeChunk]) -> int:
        vectors = self.embedding_client.embed([chunk.text for chunk in chunks])
        if len(vectors) != len(chunks):
            raise IndexingError("Embedding 返回数量与代码块数量不一致")
        if any(len(vector) != self.vector_size for vector in vectors):
            raise IndexingError("Embedding 向量维度与配置不一致")
        self.vector_store.upsert(chunks, vectors)
        return len(chunks)
