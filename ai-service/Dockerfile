FROM ghcr.io/astral-sh/uv:0.12.2 AS uv

FROM python:3.12-slim
ENV UV_PROJECT_ENVIRONMENT=/opt/venv \
    UV_LINK_MODE=copy \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PATH="/opt/venv/bin:$PATH"
COPY --from=uv /uv /uvx /bin/
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY src ./src
RUN uv sync --frozen --no-dev \
    && useradd --uid 10001 --create-home codemind
USER 10001:10001
EXPOSE 8000
CMD ["uvicorn", "codemind_ai.main:app", "--host", "0.0.0.0", "--port", "8000"]
