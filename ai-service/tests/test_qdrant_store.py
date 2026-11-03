import json
from collections.abc import Callable

import httpx
import pytest

from codemind_ai.domain.source import CodeChunk, Language, ParseStatus
from codemind_ai.infrastructure.qdrant.base import VectorStoreError
from codemind_ai.infrastructure.qdrant.http_store import HttpVectorStore

PROJECT_ID = "22222222-2222-2222-2222-222222222222"
VERSION_ID = "11111111-1111-1111-1111-111111111111"


def _client(handler: Callable[[httpx.Request], httpx.Response]) -> httpx.Client:
    return httpx.Client(transport=httpx.MockTransport(handler))


def test_collection_is_created_with_configured_dimension() -> None:
    requests: list[tuple[str, str, dict[str, object] | None]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content) if request.content else None
        requests.append((request.method, request.url.path, body))
        if request.method == "GET":
            return httpx.Response(404, json={"status": {"error": "not found"}})
        return httpx.Response(200, json={"result": True})

    store = HttpVectorStore("http://qdrant:6333", "chunks", client=_client(handler))
    store.ensure_collection(4)

    assert requests[-1] == (
        "PUT",
        "/collections/chunks",
        {"vectors": {"size": 4, "distance": "Cosine"}},
    )


def test_existing_collection_dimension_mismatch_fails() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"result": {"config": {"params": {"vectors": {"size": 3}}}}},
        )

    store = HttpVectorStore("http://qdrant:6333", "chunks", client=_client(handler))

    with pytest.raises(VectorStoreError, match="维度"):
        store.ensure_collection(4)


def test_upsert_payload_delete_filter_and_exact_count() -> None:
    requests: list[tuple[str, str, dict[str, object]]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        requests.append((request.method, request.url.path, body))
        if request.url.path.endswith("/points/count"):
            return httpx.Response(200, json={"result": {"count": 2}})
        return httpx.Response(200, json={"result": {"status": "completed"}})

    store = HttpVectorStore("http://qdrant:6333", "chunks", client=_client(handler))
    chunks = [
        CodeChunk.create(
            PROJECT_ID,
            VERSION_ID,
            "src/A.java",
            Language.JAVA,
            1,
            2,
            "class A {\n}\n",
            ParseStatus.PARSED,
        ),
        CodeChunk.create(
            PROJECT_ID,
            VERSION_ID,
            "src/b.py",
            Language.PYTHON,
            1,
            1,
            "def b(): pass\n",
            ParseStatus.PARSED,
        ),
    ]

    store.delete_version(PROJECT_ID, VERSION_ID)
    store.upsert(chunks, [[0.1, 0.2, 0.3, 0.4], [0.5, 0.6, 0.7, 0.8]])
    assert store.count_version(PROJECT_ID, VERSION_ID) == 2

    delete_filter = requests[0][2]["filter"]["must"]
    assert delete_filter == [
        {"key": "record_type", "match": {"value": "code_chunk"}},
        {"key": "project_id", "match": {"value": PROJECT_ID}},
        {"key": "source_version_id", "match": {"value": VERSION_ID}},
    ]
    points = requests[1][2]["points"]
    assert points[0]["id"] == chunks[0].chunk_id
    assert points[0]["payload"]["text"] == chunks[0].text
    assert points[0]["payload"]["relative_path"] == "src/A.java"
    assert points[0]["payload"]["start_line"] == 1
    assert points[0]["payload"]["project_id"] == PROJECT_ID


def test_qdrant_write_failure_is_sanitized() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="secret-source-code")

    store = HttpVectorStore("http://qdrant:6333", "chunks", client=_client(handler))
    chunk = CodeChunk.create(
        PROJECT_ID,
        VERSION_ID,
        "src/A.java",
        Language.JAVA,
        1,
        1,
        "class A {}\n",
        ParseStatus.PARSED,
    )

    with pytest.raises(VectorStoreError) as caught:
        store.upsert([chunk], [[0.1, 0.2, 0.3, 0.4]])

    assert "secret-source-code" not in str(caught.value)
