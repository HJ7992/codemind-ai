import re
from dataclasses import dataclass

from codemind_ai.infrastructure.chat.base import ChatClient
from codemind_ai.retrieval.service import RetrievalService

_CITATION_MARKER = re.compile(r"\[(\d+)]")


@dataclass(frozen=True, slots=True)
class Citation:
    citation_index: int
    file_path: str
    start_line: int
    end_line: int
    snippet: str
    score: float


@dataclass(frozen=True, slots=True)
class RetrievalMetadata:
    strategy: str
    top_k: int
    chat_provider: str
    chat_model: str | None
    embedding_model: str
    retrieval_duration_ms: int
    generation_duration_ms: int


@dataclass(frozen=True, slots=True)
class QaResult:
    answer: str
    citations: tuple[Citation, ...]
    retrieval_metadata: RetrievalMetadata


class QuestionAnswerService:
    def __init__(
        self,
        retrieval_service: RetrievalService,
        chat_client: ChatClient,
        embedding_model: str,
        chat_model: str,
        top_k: int = 5,
    ) -> None:
        self.retrieval_service = retrieval_service
        self.chat_client = chat_client
        self.embedding_model = embedding_model
        self.chat_model = chat_model
        self.top_k = top_k

    def answer(
        self,
        project_id: str,
        source_version_id: str,
        question: str,
        retrieval_duration_ms: int = 0,
    ) -> QaResult:
        evidence = self.retrieval_service.retrieve(
            project_id, source_version_id, question, self.top_k
        )
        if not evidence:
            return QaResult(
                answer="检索到的代码证据不足，无法回答该问题。",
                citations=(),
                retrieval_metadata=self._metadata(0, False, retrieval_duration_ms),
            )
        answer = self.chat_client.answer(question, evidence)
        answer = self._remove_invalid_markers(answer, len(evidence))
        citations = tuple(
            Citation(
                citation_index=index,
                file_path=chunk.file_path,
                start_line=chunk.start_line,
                end_line=chunk.end_line,
                snippet=chunk.snippet,
                score=chunk.score,
            )
            for index, chunk in enumerate(evidence, start=1)
        )
        return QaResult(
            answer=answer,
            citations=citations,
            retrieval_metadata=self._metadata(0, True, retrieval_duration_ms),
        )

    def _remove_invalid_markers(self, answer: str, citation_count: int) -> str:
        def replace(match: re.Match[str]) -> str:
            index = int(match.group(1))
            return match.group(0) if 1 <= index <= citation_count else ""

        return _CITATION_MARKER.sub(replace, answer).strip()

    def _metadata(
        self, generation_duration_ms: int, chat_used: bool, retrieval_duration_ms: int
    ) -> RetrievalMetadata:
        return RetrievalMetadata(
            strategy="VECTOR",
            top_k=self.top_k,
            chat_provider="CONFIGURED_OPENAI_COMPATIBLE" if chat_used else "NONE",
            chat_model=self.chat_model if chat_used else None,
            embedding_model=self.embedding_model,
            retrieval_duration_ms=retrieval_duration_ms,
            generation_duration_ms=generation_duration_ms,
        )
