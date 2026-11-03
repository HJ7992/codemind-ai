import uuid

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from codemind_ai.api.health import router as health_router
from codemind_ai.api.internal import router as internal_router
from codemind_ai.indexing.service import IndexingError
from codemind_ai.infrastructure.chat.base import ChatError
from codemind_ai.infrastructure.embedding.base import EmbeddingError
from codemind_ai.infrastructure.qdrant.base import VectorStoreError

app = FastAPI(
    title="CodeMind AI 服务",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)
app.include_router(health_router)
app.include_router(internal_router)


@app.exception_handler(ValueError)
async def handle_value_error(request: Request, exception: ValueError) -> JSONResponse:
    return _error(422, "INVALID_ARGUMENT", str(exception), request)


@app.exception_handler(IndexingError)
async def handle_indexing_error(request: Request, exception: IndexingError) -> JSONResponse:
    return _error(422, "INDEXING_FAILED", str(exception), request)


@app.exception_handler(EmbeddingError)
@app.exception_handler(ChatError)
@app.exception_handler(VectorStoreError)
async def handle_external_error(request: Request, exception: Exception) -> JSONResponse:
    return _error(502, "EXTERNAL_DEPENDENCY_FAILED", str(exception), request)


def _error(status: int, code: str, message: str, request: Request) -> JSONResponse:
    trace_id = request.headers.get("X-Trace-Id") or str(uuid.uuid4())
    return JSONResponse(
        status_code=status,
        content={"errorCode": code, "message": message, "traceId": trace_id},
    )
