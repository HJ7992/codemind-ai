from typing import Any

from pydantic import BaseModel, Field


class IndexRequest(BaseModel):
    jobId: str
    projectId: str
    sourceVersionId: str
    storageKey: str = Field(min_length=1, max_length=512)


class SourceFileResponse(BaseModel):
    relativePath: str
    language: str
    sizeBytes: int
    lineCount: int
    sha256: str
    parseStatus: str


class IndexResponse(BaseModel):
    jobId: str
    projectId: str
    sourceVersionId: str
    status: str
    sourceFileCount: int
    chunkCount: int
    embeddingModel: str
    sourceFiles: list[SourceFileResponse]


class RetrievalRequest(BaseModel):
    projectId: str
    sourceVersionId: str
    query: str = Field(min_length=1, max_length=4000)
    topK: int | None = Field(default=None, ge=1, le=20)


class RetrievedChunkResponse(BaseModel):
    chunkId: str
    filePath: str
    startLine: int
    endLine: int
    snippet: str
    score: float


class RetrievalResponse(BaseModel):
    sourceVersionId: str
    strategy: str = "VECTOR"
    chunks: list[RetrievedChunkResponse]


class QaRequest(BaseModel):
    projectId: str
    sourceVersionId: str
    question: str = Field(min_length=1, max_length=4000)


class CitationResponse(BaseModel):
    citationIndex: int
    filePath: str
    startLine: int
    endLine: int
    snippet: str
    score: float


class RetrievalMetadataResponse(BaseModel):
    strategy: str
    topK: int
    chatProvider: str
    chatModel: str | None
    embeddingModel: str
    retrievalDurationMs: int
    generationDurationMs: int


class QaResponse(BaseModel):
    sourceVersionId: str
    answer: str
    citations: list[CitationResponse]
    retrievalMetadata: RetrievalMetadataResponse


class ErrorResponse(BaseModel):
    errorCode: str
    message: str
    traceId: str


class ErrorEnvelope(BaseModel):
    detail: ErrorResponse | None = None
    payload: dict[str, Any] | None = None
