import json
from collections.abc import Callable

import httpx
import pytest

from codemind_ai.infrastructure.embedding.base import EmbeddingError
from codemind_ai.infrastructure.embedding.openai_client import OpenAIEmbeddingClient


def _client(handler: Callable[[httpx.Request], httpx.Response]) -> httpx.Client:
    return httpx.Client(transport=httpx.MockTransport(handler))


def test_embedding_batches_only_code_texts_and_validates_dimensions() -> None:
    requests: list[dict[str, object]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["Authorization"] == "Bearer test-key"
        body = json.loads(request.content)
        requests.append(body)
        data = [
            {"index": index, "embedding": [float(index), 1.0]}
            for index, _ in enumerate(body["input"])
        ]
        return httpx.Response(200, json={"data": list(reversed(data))})

    client = OpenAIEmbeddingClient(
        "https://embedding.example/v1",
        "embedding-test",
        "test-key",
        dimensions=2,
        batch_size=2,
        client=_client(handler),
    )

    vectors = client.embed(["class A {}", "def b(): pass", "class C {}"])

    assert vectors == [[0.0, 1.0], [1.0, 1.0], [0.0, 1.0]]
    assert [len(request["input"]) for request in requests] == [2, 1]
    assert all(request["model"] == "embedding-test" for request in requests)
    assert all("test-key" not in json.dumps(request) for request in requests)


def test_embedding_http_error_is_sanitized() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="upstream-secret-source")

    client = OpenAIEmbeddingClient(
        "https://embedding.example/v1",
        "embedding-test",
        "test-key",
        dimensions=2,
        client=_client(handler),
    )

    with pytest.raises(EmbeddingError) as caught:
        client.embed(["class Secret {}"])

    assert "upstream-secret-source" not in str(caught.value)
    assert "test-key" not in str(caught.value)


def test_embedding_dimension_mismatch_fails() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"data": [{"index": 0, "embedding": [1.0]}]})

    client = OpenAIEmbeddingClient(
        "https://embedding.example/v1",
        "embedding-test",
        "test-key",
        dimensions=2,
        client=_client(handler),
    )

    with pytest.raises(EmbeddingError, match="维度"):
        client.embed(["class A {}"])
