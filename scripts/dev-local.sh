#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$ROOT_DIR"

if [[ ! -f deploy/.env ]]; then
    printf '请先复制 deploy/env.example 为 deploy/.env，并替换本地占位符。\n' >&2
    exit 1
fi

set -a
source deploy/.env
set +a

MYSQLD_BIN=${MYSQLD_BIN:-mysqld}
MYSQL_BIN=${MYSQL_BIN:-mysql}
MYSQLADMIN_BIN=${MYSQLADMIN_BIN:-mysqladmin}
QDRANT_BIN=${QDRANT_BIN:-qdrant}
if [[ ! -x "$QDRANT_BIN" || -d "$QDRANT_BIN" ]] && [[ -f "$ROOT_DIR/deploy/data/tools/qdrant/qdrant.exe" ]]; then
    QDRANT_BIN="$ROOT_DIR/deploy/data/tools/qdrant/qdrant.exe"
fi
MYSQL_PORT=${CODEMIND_LOCAL_MYSQL_PORT:-3307}
if [[ -z "${MYSQL_BASE_DIR:-}" ]]; then
    MYSQL_BASE_DIR=$(cd "$(dirname "$(command -v "$MYSQLD_BIN")")/.." && pwd)
fi
DATA_DIR="$ROOT_DIR/deploy/data"
MYSQL_DATA_DIR="$DATA_DIR/mysql"
LOG_DIR="$DATA_DIR/logs"
# 本机进程不使用 Compose 容器内路径/主机名，统一覆盖为仓库内绝对路径与本机端口。
export CODEMIND_SOURCE_ROOT="$DATA_DIR/sources"
export CODEMIND_AI_BASE_URL="http://127.0.0.1:8000"
PIDS=()

mkdir -p "$LOG_DIR" "$DATA_DIR/sources"

cleanup() {
    local exit_code=$?
    trap - EXIT
    for pid in "${PIDS[@]:-}"; do
        kill "$pid" >/dev/null 2>&1 || true
    done
    if [[ -n "${MYSQL_PID:-}" ]]; then
        "$MYSQLADMIN_BIN" --protocol=TCP -h127.0.0.1 -P"$MYSQL_PORT" -uroot shutdown >/dev/null 2>&1 || true
    fi
    wait >/dev/null 2>&1 || true
    exit "$exit_code"
}
trap cleanup EXIT INT TERM

if [[ ! -d "$MYSQL_DATA_DIR/mysql" ]]; then
    printf '初始化本地 MySQL 数据目录...\n'
    mkdir -p "$MYSQL_DATA_DIR"
    "$MYSQLD_BIN" --no-defaults --initialize-insecure \
        --basedir="$MYSQL_BASE_DIR" \
        --datadir="$MYSQL_DATA_DIR"
fi

printf '启动本地 MySQL：127.0.0.1:%s\n' "$MYSQL_PORT"
"$MYSQLD_BIN" --no-defaults \
    --datadir="$MYSQL_DATA_DIR" \
    --port="$MYSQL_PORT" \
    --bind-address=127.0.0.1 \
    --skip-named-pipe \
    --console >"$LOG_DIR/mysql.log" 2>&1 &
MYSQL_PID=$!
PIDS+=("$MYSQL_PID")

for _ in {1..30}; do
    if "$MYSQLADMIN_BIN" --protocol=TCP -h127.0.0.1 -P"$MYSQL_PORT" -uroot ping --silent >/dev/null 2>&1; then
        break
    fi
    sleep 1
done

"$MYSQL_BIN" --protocol=TCP -h127.0.0.1 -P"$MYSQL_PORT" -uroot -e \
    "CREATE DATABASE IF NOT EXISTS codemind CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; CREATE USER IF NOT EXISTS 'codemind'@'%' IDENTIFIED BY '$CODEMIND_DATABASE_PASSWORD'; GRANT ALL PRIVILEGES ON codemind.* TO 'codemind'@'%'; FLUSH PRIVILEGES;"

if command -v "$QDRANT_BIN" >/dev/null 2>&1 || [[ -x "$QDRANT_BIN" ]]; then
    printf '启动本地 Qdrant：127.0.0.1:6333\n'
    QDRANT__SERVICE__HOST=127.0.0.1 \
        QDRANT__STORAGE__STORAGE_PATH="$DATA_DIR/qdrant-storage" \
        "$QDRANT_BIN" >"$LOG_DIR/qdrant.log" 2>&1 &
    QDRANT_PID=$!
    PIDS+=("$QDRANT_PID")
else
    printf '警告：未找到 Qdrant，AI 就绪检查将保持 DOWN。可稍后再安装独立 Qdrant。\n'
fi

printf '启动 Spring Boot：127.0.0.1:8080\n'
(
    cd "$ROOT_DIR/backend"
    export CODEMIND_DATABASE_URL="jdbc:mysql://127.0.0.1:$MYSQL_PORT/codemind?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    if [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]; then
        ./mvnw.cmd spring-boot:run >"$LOG_DIR/backend.log" 2>&1
    else
        ./mvnw spring-boot:run >"$LOG_DIR/backend.log" 2>&1
    fi
) &
BACKEND_PID=$!
PIDS+=("$BACKEND_PID")

printf '启动 FastAPI：127.0.0.1:8000\n'
(
    cd "$ROOT_DIR/ai-service"
    export CODEMIND_QDRANT_URL="http://127.0.0.1:6333"
    uv run uvicorn codemind_ai.main:app --host 127.0.0.1 --port 8000 >"$LOG_DIR/ai-service.log" 2>&1
) &
AI_PID=$!
PIDS+=("$AI_PID")

printf '启动 React：127.0.0.1:5173\n'
(
    cd "$ROOT_DIR/frontend"
    npm run dev -- --port 5173 >"$LOG_DIR/frontend.log" 2>&1
) &
FRONTEND_PID=$!
PIDS+=("$FRONTEND_PID")

printf '\n本地服务已启动。按 Ctrl+C 可停止本次启动的全部服务。\n'
printf '日志目录：%s\n' "$LOG_DIR"
wait -n
