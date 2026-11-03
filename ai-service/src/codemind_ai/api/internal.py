import hmac
import threading
from pathlib import Path
from typing import Annotated

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, Response

from codemind_ai.api.models import (
    CitationResponse,
    IndexRequest,
    IndexResponse,
    QaRequest,
    QaResponse,
    RetrievalMetadataResponse,
    RetrievalRequest,
    RetrievalResponse,
    RetrievedChunkResponse,
    SourceFileResponse,
)
from codemind_ai.core.config import Settings, get_settings
from codemind_ai.indexing.service import SourceIndexingService
from codemind_ai.infrastructure.chat.openai_client import OpenAIChatClient
from codemind_ai.infrastructure.embedding.openai_client import OpenAIEmbeddingClient
from codemind_ai.infrastructure.qdrant.http_store import HttpVectorStore
from codemind_ai.parsing.service import SourceParsingService
from codemind_ai.qa.service import QuestionAnswerService
from codemind_ai.retrieval.service import RetrievalService

router = APIRouter(prefix="/internal/v1")
_VERSION_LOCKS: dict[str, threading.Lock] = {}
_LOCKS_GUARD = threading.Lock()


def verify_internal_token(
    settings: Annotated[Settings, Depends(get_settings)],
    authorization: Annotated[str | None, Header()] = None,
) -> None:
    configured = settings.internal_service_token
    if configured is None or not configured.get_secret_value():
        raise HTTPException(status_code=503, detail="内部服务凭证未配置")
    expected = f"Bearer {configured.get_secret_value()}"
    if authorization is None or not hmac.compare_digest(authorization, expected):
        raise HTTPException(status_code=401, detail="内部服务认证失败")


def _embedding(settings: Settings) -> OpenAIEmbeddingClient:
    return OpenAIEmbeddingClient(
        base_url=settings.embedding_base_url,
        model=settings.embedding_model,
        api_key=settings.embedding_api_key.get_secret_value() if settings.embedding_api_key else "",
        dimensions=settings.embedding_dimensions,
        batch_size=settings.embedding_batch_size,
        timeout_seconds=settings.embedding_timeout_seconds,
    )


def _store(settings: Settings) -> HttpVectorStore:
    return HttpVectorStore(
        settings.qdrant_url, settings.qdrant_collection, settings.qdrant_timeout_seconds
    )


def _lock(source_version_id: str) -> threading.Lock:
    with _LOCKS_GUARD:
        return _VERSION_LOCKS.setdefault(source_version_id, threading.Lock())


def _discover(root: Path) -> tuple[Path, list[str]]:
    resolved_root = root.resolve()
    if not resolved_root.is_dir():
        raise HTTPException(status_code=404, detail="源码版本目录不存在")
    paths = sorted(
        path.relative_to(resolved_root).as_posix()
        for path in resolved_root.rglob("*")
        if path.is_file()
        and path.suffix.lower() in {".java", ".py"}
        and path.resolve().is_relative_to(resolved_root)
    )
    return resolved_root, paths


@router.post("/indexes", dependencies=[Depends(verify_internal_token)])
def create_index(request: IndexRequest, http_request: Request) -> IndexResponse:
    settings = get_settings()
    source_root = Path(settings.source_root).resolve()
    storage_root = (source_root / request.storageKey).resolve()
    if not storage_root.is_relative_to(source_root):
        raise HTTPException(status_code=400, detail="源码存储路径无效")
    lock = _lock(request.sourceVersionId)
    if not lock.acquire(blocking=False):
        raise HTTPException(status_code=409, detail="相同源码版本正在索引")
    try:
        root, relative_paths = _discover(storage_root)
        service = SourceIndexingService(
            parsing_service=SourceParsingService(),
            embedding_client=_embedding(settings),
            vector_store=_store(settings),
            vector_size=settings.embedding_dimensions,
            embedding_batch_size=settings.embedding_batch_size,
        )
        summary = service.index(root, request.projectId, request.sourceVersionId, relative_paths)
        return IndexResponse(
            jobId=request.jobId,
            projectId=request.projectId,
            sourceVersionId=request.sourceVersionId,
            status="SUCCEEDED",
            sourceFileCount=len(summary.source_files),
            chunkCount=summary.chunk_count,
            embeddingModel=settings.embedding_model,
            sourceFiles=[
                SourceFileResponse(
                    relativePath=file.relative_path,
                    language=file.language.value,
                    sizeBytes=file.size_bytes,
                    lineCount=file.line_count,
                    sha256=file.sha256,
                    parseStatus=file.parse_status.value,
                )
                for file in summary.source_files
            ],
        )
    finally:
        lock.release()


@router.post("/retrievals", dependencies=[Depends(verify_internal_token)])
def retrieve(request: RetrievalRequest) -> RetrievalResponse:
    settings = get_settings()
    service = RetrievalService(
        embedding_client=_embedding(settings),
        vector_store=_store(settings),
        default_top_k=5,
        max_top_k=20,
    )
    chunks = service.retrieve(
        request.projectId, request.sourceVersionId, request.query, request.topK
    )
    return RetrievalResponse(
        sourceVersionId=request.sourceVersionId,
        chunks=[
            RetrievedChunkResponse(
                chunkId=chunk.chunk_id,
                filePath=chunk.file_path,
                startLine=chunk.start_line,
                endLine=chunk.end_line,
                snippet=chunk.snippet,
                score=chunk.score,
            )
            for chunk in chunks
        ],
    )


@router.post("/qa", dependencies=[Depends(verify_internal_token)])
def answer(request: QaRequest) -> QaResponse:
    settings = get_settings()
    retrieval = RetrievalService(
        embedding_client=_embedding(settings),
        vector_store=_store(settings),
        default_top_k=5,
        max_top_k=20,
    )
    chat = OpenAIChatClient(
        settings.chat_base_url,
        settings.chat_model,
        settings.chat_api_key.get_secret_value() if settings.chat_api_key else "",
        45.0,
    )
    result = QuestionAnswerService(
        retrieval,
        chat,
        embedding_model=settings.embedding_model,
        chat_model=settings.chat_model,
        top_k=5,
    ).answer(request.projectId, request.sourceVersionId, request.question)
    return QaResponse(
        sourceVersionId=request.sourceVersionId,
        answer=result.answer,
        citations=[
            CitationResponse(
                citationIndex=item.citation_index,
                filePath=item.file_path,
                startLine=item.start_line,
                endLine=item.end_line,
                snippet=item.snippet,
                score=item.score,
            )
            for item in result.citations
        ],
        retrievalMetadata=RetrievalMetadataResponse(
            strategy=result.retrieval_metadata.strategy,
            topK=result.retrieval_metadata.top_k,
            chatProvider=result.retrieval_metadata.chat_provider,
            chatModel=result.retrieval_metadata.chat_model,
            embeddingModel=result.retrieval_metadata.embedding_model,
            retrievalDurationMs=result.retrieval_metadata.retrieval_duration_ms,
            generationDurationMs=result.retrieval_metadata.generation_duration_ms,
        ),
    )


@router.delete(
    "/indexes/{source_version_id}",
    status_code=204,
    dependencies=[Depends(verify_internal_token)],
)
def delete_index(source_version_id: str, project_id: str = Query(min_length=1)) -> Response:
    settings = get_settings()
    lock = _lock(source_version_id)
    if not lock.acquire(blocking=False):
        raise HTTPException(status_code=409, detail="相同源码版本正在执行索引操作")
    try:
        _store(settings).delete_version(project_id, source_version_id)
        return Response(status_code=204)
    finally:
        lock.release()
