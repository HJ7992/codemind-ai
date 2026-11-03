import os
import uuid

import httpx
import pytest

from codemind_ai.domain.source import CodeChunk, Language, ParseStatus
from codemind_ai.infrastructure.qdrant.http_store import HttpVectorStore


@pytest.mark.skipif(not os.getenv("CODEMIND_TEST_QDRANT_URL"), reason="未提供真实 Qdrant 测试地址")
def test_real_qdrant_round_trip_and_project_isolation() -> None:
    store = HttpVectorStore(
        os.environ["CODEMIND_TEST_QDRANT_URL"],
        f"codemind_test_{uuid.uuid4().hex}",
    )
    project_a = str(uuid.uuid4())
    project_b = str(uuid.uuid4())
    version = str(uuid.uuid4())
    store.ensure_collection(4)
    chunk = CodeChunk.create(
        project_a,
        version,
        "demo/src/Main.java",
        Language.JAVA,
        1,
        2,
        "class Main {\n}\n",
        ParseStatus.PARSED,
    )

    try:
        store.upsert([chunk], [[0.1, 0.2, 0.3, 0.4]])
        assert store.count_version(project_a, version) == 1
        assert store.count_version(project_b, version) == 0

        store.delete_version(project_a, version)
        assert store.count_version(project_a, version) == 0
    finally:
        try:
            httpx.delete(
                f"{os.environ['CODEMIND_TEST_QDRANT_URL'].rstrip('/')}/collections/{store.collection}",
                timeout=10,
            )
        except httpx.HTTPError:
            pass
