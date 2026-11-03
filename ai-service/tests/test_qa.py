from codemind_ai.domain.source import CodeChunk, Language, ParseStatus
from codemind_ai.qa.service import QuestionAnswerService
from codemind_ai.retrieval.service import RetrievalService
from tests.fake_chat import FakeChatClient
from tests.fakes import FakeEmbeddingClient, InMemoryVectorStore


def test_qa_without_evidence_does_not_call_chat() -> None:
    chat = FakeChatClient()

    result = _service(chat, InMemoryVectorStore()).answer("p", "v", "登录在哪里？")

    assert result.citations == ()
    assert "证据不足" in result.answer
    assert not chat.calls


def test_qa_returns_real_citation_location_and_removes_invalid_markers() -> None:
    embeddings = FakeEmbeddingClient(dimensions=4)
    store = InMemoryVectorStore()
    store.ensure_collection(4)
    store.upsert([_chunk()], [[0.1, 0.2, 0.3, 0.4]])
    chat = FakeChatClient("登录逻辑见 [1]，另见不存在的 [9]。")

    result = _service(chat, store, embeddings).answer("p", "v", "登录在哪里？")

    assert result.answer == "登录逻辑见 [1]，另见不存在的 。"
    assert len(result.citations) == 1
    assert result.citations[0].file_path == "src/Auth.java"
    assert result.citations[0].start_line == 10
    assert result.citations[0].end_line == 18
    assert chat.calls[0][1][0].snippet.startswith("public boolean login")


def _service(
    chat: FakeChatClient,
    store: InMemoryVectorStore,
    embeddings: FakeEmbeddingClient | None = None,
) -> QuestionAnswerService:
    retrieval = RetrievalService(embeddings or FakeEmbeddingClient(dimensions=4), store)
    return QuestionAnswerService(
        retrieval,
        chat,
        embedding_model="fake-embedding",
        chat_model="fake-chat",
    )


def _chunk() -> CodeChunk:
    return CodeChunk.create(
        "p",
        "v",
        "src/Auth.java",
        Language.JAVA,
        10,
        18,
        "public boolean login(String name) { return true; }\n",
        ParseStatus.PARSED,
    )
