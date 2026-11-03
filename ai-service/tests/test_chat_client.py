import json

import httpx
import pytest

from codemind_ai.infrastructure.chat.base import ChatError
from codemind_ai.infrastructure.chat.openai_client import OpenAIChatClient
from codemind_ai.infrastructure.qdrant.base import StoredChunk


def _evidence() -> list[StoredChunk]:
    return [
        StoredChunk(
            chunk_id="chunk-1",
            file_path="src/Auth.java",
            start_line=10,
            end_line=18,
            snippet="public boolean login(String name) { return true; }",
            score=0.91,
        )
    ]


def test_chat_sends_only_question_and_retrieved_evidence() -> None:
    bodies: list[dict[str, object]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        bodies.append(json.loads(request.content))
        return httpx.Response(200, json={"choices": [{"message": {"content": "登录逻辑见 [1]。"}}]})

    client = OpenAIChatClient(
        "https://chat.example/v1",
        "chat-test",
        "test-key",
        client=httpx.Client(transport=httpx.MockTransport(handler)),
    )

    answer = client.answer("登录在哪里？", _evidence())

    assert answer == "登录逻辑见 [1]。"
    user_content = bodies[0]["messages"][1]["content"]
    assert "src/Auth.java:10-18" in user_content
    assert "public boolean login" in user_content
    assert "test-key" not in json.dumps(bodies[0])


def test_chat_error_does_not_leak_upstream_body() -> None:
    client = OpenAIChatClient(
        "https://chat.example/v1",
        "chat-test",
        "test-key",
        client=httpx.Client(
            transport=httpx.MockTransport(
                lambda request: httpx.Response(500, text="private-source")
            )
        ),
    )

    with pytest.raises(ChatError) as caught:
        client.answer("问题", _evidence())

    assert "private-source" not in str(caught.value)
