from codemind_ai.infrastructure.embedding.base import EmbeddingClient
from codemind_ai.infrastructure.qdrant.base import StoredChunk, VectorStore


class RetrievalService:
    def __init__(
        self,
        embedding_client: EmbeddingClient,
        vector_store: VectorStore,
        default_top_k: int = 5,
        max_top_k: int = 20,
    ) -> None:
        if not 1 <= default_top_k <= max_top_k:
            raise ValueError("默认 topK 必须位于有效范围内")
        self.embedding_client = embedding_client
        self.vector_store = vector_store
        self.default_top_k = default_top_k
        self.max_top_k = max_top_k

    def retrieve(
        self,
        project_id: str,
        source_version_id: str,
        query: str,
        top_k: int | None = None,
    ) -> list[StoredChunk]:
        normalized = query.strip()
        if not normalized or len(normalized) > 4000:
            raise ValueError("查询长度必须在 1 到 4000 个字符之间")
        limit = top_k or self.default_top_k
        if not 1 <= limit <= self.max_top_k:
            raise ValueError("topK 超出允许范围")
        vectors = self.embedding_client.embed([normalized])
        if len(vectors) != 1:
            raise ValueError("查询向量数量无效")
        return self.vector_store.search(
            project_id=project_id,
            source_version_id=source_version_id,
            query_vector=vectors[0],
            limit=limit,
        )
