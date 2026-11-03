from pathlib import Path

import pytest

from codemind_ai.indexing.service import IndexingError, SourceIndexingService
from codemind_ai.parsing.chunking import SourceChunker
from codemind_ai.parsing.service import SourceParsingService
from tests.fakes import FakeEmbeddingClient, InMemoryVectorStore

PROJECT_ID = "22222222-2222-2222-2222-222222222222"
VERSION_ID = "11111111-1111-1111-1111-111111111111"


def test_index_pipeline_batches_embeddings_and_verifies_point_count(tmp_path: Path) -> None:
    _write_sources(tmp_path)
    embeddings = FakeEmbeddingClient(dimensions=4)
    vectors = InMemoryVectorStore()
    service = _service(embeddings, vectors, batch_size=2)

    summary = service.index(
        tmp_path, PROJECT_ID, VERSION_ID, ["src/a.py", "src/b.py", "src/C.java"]
    )

    assert summary.chunk_count == len(vectors.points) > 2
    assert len(embeddings.batches) >= 2
    assert all(len(batch) <= 2 for batch in embeddings.batches)
    assert all(payload["project_id"] == PROJECT_ID for _, payload in vectors.points.values())
    assert all(payload["source_version_id"] == VERSION_ID for _, payload in vectors.points.values())
    assert vectors.deleted_versions[0] == (PROJECT_ID, VERSION_ID)


def test_repeated_index_cleans_version_instead_of_duplicating(tmp_path: Path) -> None:
    _write_sources(tmp_path)
    vectors = InMemoryVectorStore()
    service = _service(FakeEmbeddingClient(), vectors, batch_size=2)

    first = service.index(tmp_path, PROJECT_ID, VERSION_ID, ["src/a.py", "src/b.py"])
    second = service.index(tmp_path, PROJECT_ID, VERSION_ID, ["src/a.py", "src/b.py"])

    assert first.chunk_count == second.chunk_count
    assert len(vectors.points) == second.chunk_count
    assert len(vectors.deleted_versions) == 2


def test_embedding_failure_removes_partial_vectors(tmp_path: Path) -> None:
    _write_sources(tmp_path)
    vectors = InMemoryVectorStore()
    service = _service(FakeEmbeddingClient(fail_on_batch=2), vectors, batch_size=1)

    with pytest.raises(RuntimeError, match="模拟 Embedding 失败"):
        service.index(tmp_path, PROJECT_ID, VERSION_ID, ["src/a.py", "src/b.py"])

    assert not vectors.points


def test_empty_paths_are_rejected() -> None:
    service = _service(FakeEmbeddingClient(), InMemoryVectorStore())

    with pytest.raises(IndexingError, match="没有源码文件"):
        service.index(Path("."), PROJECT_ID, VERSION_ID, [])


def test_embedding_dimension_mismatch_fails(tmp_path: Path) -> None:
    _write_sources(tmp_path)
    vectors = InMemoryVectorStore()
    service = SourceIndexingService(
        parsing_service=SourceParsingService(),
        embedding_client=FakeEmbeddingClient(dimensions=3),
        vector_store=vectors,
        vector_size=4,
    )

    with pytest.raises(IndexingError, match="维度"):
        service.index(tmp_path, PROJECT_ID, VERSION_ID, ["src/a.py"])

    assert not vectors.points


def _service(
    embeddings: FakeEmbeddingClient, vectors: InMemoryVectorStore, batch_size: int = 16
) -> SourceIndexingService:
    return SourceIndexingService(
        parsing_service=SourceParsingService(SourceChunker(max_lines=3, overlap_lines=1)),
        embedding_client=embeddings,
        vector_store=vectors,
        vector_size=4,
        embedding_batch_size=batch_size,
    )


def _write_sources(root: Path) -> None:
    (root / "src").mkdir()
    (root / "src/a.py").write_text(
        "def first():\n    return 1\n\ndef second():\n    return 2\n", encoding="utf-8"
    )
    (root / "src/b.py").write_text(
        "def third():\n    return 3\n\ndef fourth():\n    return 4\n", encoding="utf-8"
    )
    (root / "src/C.java").write_text(
        "class C {\n    void one() {}\n    void two() {}\n}\n", encoding="utf-8"
    )
