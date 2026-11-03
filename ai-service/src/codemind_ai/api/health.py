import httpx
from fastapi import APIRouter
from fastapi.responses import JSONResponse

from codemind_ai.core.config import Settings, get_settings

router = APIRouter(prefix="/health", tags=["健康检查"])


@router.get("/live")
def live() -> dict[str, str]:
    """进程能够响应即视为存活，不访问任何外部依赖。"""
    return {"status": "UP"}


@router.get("/ready")
def ready() -> JSONResponse:
    settings = get_settings()
    configuration_up = settings.configuration_is_valid()
    qdrant_up = configuration_up and _qdrant_is_ready(settings)
    healthy = configuration_up and qdrant_up
    return JSONResponse(
        status_code=200 if healthy else 503,
        content={
            "status": "UP" if healthy else "DOWN",
            "checks": {
                "configuration": "UP" if configuration_up else "DOWN",
                "qdrant": "UP" if qdrant_up else "DOWN",
            },
        },
    )


def _qdrant_is_ready(settings: Settings) -> bool:
    """只检查 Qdrant，不就绪探测付费模型，避免产生额外费用。"""
    try:
        response = httpx.get(f"{settings.qdrant_url.rstrip('/')}/healthz", timeout=2.0)
        return response.status_code == 200
    except httpx.HTTPError:
        return False
