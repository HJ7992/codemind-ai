from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from codemind_ai.core.config import get_settings
from codemind_ai.main import app


@pytest.fixture(autouse=True)
def clear_settings_cache() -> Iterator[None]:
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


@pytest.fixture
def valid_model_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CODEMIND_QDRANT_URL", "http://qdrant:6333")
    monkeypatch.setenv("CODEMIND_CHAT_BASE_URL", "https://chat.example/v1")
    monkeypatch.setenv("CODEMIND_CHAT_MODEL", "chat-test")
    monkeypatch.setenv("CODEMIND_CHAT_API_KEY", "test-chat-key")
    monkeypatch.setenv("CODEMIND_EMBEDDING_BASE_URL", "https://embedding.example/v1")
    monkeypatch.setenv("CODEMIND_EMBEDDING_MODEL", "embedding-test")
    monkeypatch.setenv("CODEMIND_EMBEDDING_API_KEY", "test-embedding-key")
    monkeypatch.setenv("CODEMIND_INTERNAL_SERVICE_TOKEN", "test-internal-token")


def test_live_does_not_require_dependencies() -> None:
    response = TestClient(app).get("/health/live")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def test_ready_returns_aggregate_checks_without_sensitive_configuration(
    valid_model_environment: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr("codemind_ai.api.health._qdrant_is_ready", lambda settings: True)

    response = TestClient(app).get("/health/ready")

    assert response.status_code == 200
    assert response.json() == {
        "status": "UP",
        "checks": {"configuration": "UP", "qdrant": "UP"},
    }
    assert "test-chat-key" not in response.text
    assert "chat.example" not in response.text


def test_ready_reports_qdrant_failure_without_calling_models(
    valid_model_environment: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr("codemind_ai.api.health._qdrant_is_ready", lambda settings: False)

    response = TestClient(app).get("/health/ready")

    assert response.status_code == 503
    assert response.json() == {
        "status": "DOWN",
        "checks": {"configuration": "UP", "qdrant": "DOWN"},
    }


def test_ready_rejects_incomplete_configuration(monkeypatch: pytest.MonkeyPatch) -> None:
    for name in (
        "CODEMIND_CHAT_BASE_URL",
        "CODEMIND_CHAT_MODEL",
        "CODEMIND_CHAT_API_KEY",
        "CODEMIND_EMBEDDING_BASE_URL",
        "CODEMIND_EMBEDDING_MODEL",
        "CODEMIND_EMBEDDING_API_KEY",
        "CODEMIND_INTERNAL_SERVICE_TOKEN",
    ):
        monkeypatch.delenv(name, raising=False)
    qdrant_called = False

    def unexpected_qdrant_call(settings: object) -> bool:
        nonlocal qdrant_called
        qdrant_called = True
        return True

    monkeypatch.setattr("codemind_ai.api.health._qdrant_is_ready", unexpected_qdrant_call)

    response = TestClient(app).get("/health/ready")

    assert response.status_code == 503
    assert response.json()["checks"] == {"configuration": "DOWN", "qdrant": "DOWN"}
    assert qdrant_called is False
