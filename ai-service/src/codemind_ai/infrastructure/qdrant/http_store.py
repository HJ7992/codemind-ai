from collections.abc import Sequence
from typing import Any

import httpx

from codemind_ai.domain.source import CodeChunk
from codemind_ai.infrastructure.qdrant.base import StoredChunk, VectorStoreError

_UPSERT_BATCH_SIZE = 64


class HttpVectorStore:
    """通过 Qdrant REST API 写入和清理代码块向量。"""

    def __init__(
        self,
        base_url: str,
        collection: str,
        timeout_seconds: float = 10.0,
        client: httpx.Client | None = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.collection = collection
        self.timeout_seconds = timeout_seconds
        self.client = client

    def ensure_collection(self, vector_size: int) -> None:
        if vector_size <= 0:
            raise ValueError("向量维度必须大于 0")
        response = self._request("GET", f"/collections/{self.collection}")
        if response.status_code == 200:
            try:
                vectors = response.json()["result"]["config"]["params"]["vectors"]
                existing_size = vectors["size"]
            except (KeyError, TypeError) as exception:
                raise VectorStoreError("Qdrant 集合配置无法识别") from exception
            if existing_size != vector_size:
                raise VectorStoreError("Qdrant 集合向量维度与当前 Embedding 配置不一致")
            return
        if response.status_code != 404:
            raise VectorStoreError("Qdrant 集合检查失败")
        created = self._request(
            "PUT",
            f"/collections/{self.collection}",
            json={"vectors": {"size": vector_size, "distance": "Cosine"}},
        )
        if created.status_code not in {200, 201, 409}:
            raise VectorStoreError("Qdrant 集合创建失败")

    def delete_version(self, project_id: str, source_version_id: str) -> None:
        response = self._request(
            "POST",
            f"/collections/{self.collection}/points/delete?wait=true",
            json={"filter": self._version_filter(project_id, source_version_id)},
        )
        if response.status_code not in {200, 404}:
            raise VectorStoreError("Qdrant 旧索引清理失败")

    def upsert(self, chunks: Sequence[CodeChunk], vectors: Sequence[Sequence[float]]) -> None:
        if len(chunks) != len(vectors):
            raise VectorStoreError("代码块数量与向量数量不一致")
        for offset in range(0, len(chunks), _UPSERT_BATCH_SIZE):
            batch_chunks = chunks[offset : offset + _UPSERT_BATCH_SIZE]
            batch_vectors = vectors[offset : offset + _UPSERT_BATCH_SIZE]
            points = [
                {
                    "id": chunk.chunk_id,
                    "vector": list(vector),
                    "payload": {
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
                }
                for chunk, vector in zip(batch_chunks, batch_vectors, strict=True)
            ]
            response = self._request(
                "PUT",
                f"/collections/{self.collection}/points?wait=true",
                json={"points": points},
            )
            if response.status_code != 200:
                raise VectorStoreError("Qdrant 向量写入失败")

    def count_version(self, project_id: str, source_version_id: str) -> int:
        response = self._request(
            "POST",
            f"/collections/{self.collection}/points/count",
            json={"exact": True, "filter": self._version_filter(project_id, source_version_id)},
        )
        if response.status_code != 200:
            raise VectorStoreError("Qdrant 点数校验失败")
        try:
            return int(response.json()["result"]["count"])
        except (KeyError, TypeError, ValueError) as exception:
            raise VectorStoreError("Qdrant 点数响应无效") from exception

    def search(
        self,
        project_id: str,
        source_version_id: str,
        query_vector: Sequence[float],
        limit: int,
    ) -> list[StoredChunk]:
        if limit < 1:
            raise ValueError("检索数量必须大于 0")
        response = self._request(
            "POST",
            f"/collections/{self.collection}/points/query",
            json={
                "query": list(query_vector),
                "limit": limit,
                "with_payload": True,
                "filter": self._version_filter(project_id, source_version_id),
            },
        )
        if response.status_code != 200:
            raise VectorStoreError("Qdrant 检索失败")
        try:
            points = response.json()["result"]["points"]
            return [
                StoredChunk(
                    chunk_id=point["payload"]["chunk_id"],
                    file_path=point["payload"]["relative_path"],
                    start_line=int(point["payload"]["start_line"]),
                    end_line=int(point["payload"]["end_line"]),
                    snippet=point["payload"]["text"],
                    score=float(point["score"]),
                )
                for point in points
            ]
        except (KeyError, TypeError, ValueError) as exception:
            raise VectorStoreError("Qdrant 检索响应无效") from exception

    def _version_filter(self, project_id: str, source_version_id: str) -> dict[str, Any]:
        return {
            "must": [
                {"key": "record_type", "match": {"value": "code_chunk"}},
                {"key": "project_id", "match": {"value": project_id}},
                {"key": "source_version_id", "match": {"value": source_version_id}},
            ]
        }

    def _request(
        self, method: str, path: str, json: dict[str, Any] | None = None
    ) -> httpx.Response:
        try:
            if self.client is not None:
                return self.client.request(
                    method, f"{self.base_url}{path}", json=json, timeout=self.timeout_seconds
                )
            with httpx.Client(timeout=self.timeout_seconds) as client:
                return client.request(method, f"{self.base_url}{path}", json=json)
        except httpx.HTTPError as exception:
            raise VectorStoreError("Qdrant 服务暂时不可用") from exception
