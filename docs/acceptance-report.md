# CodeMind AI MVP 验收报告

- 验收日期：2026-11-03
- 环境：Windows 11、Java 26 运行/Maven 编译目标 21、Python 3.12、Node 24、MySQL 26.10.0、Qdrant 1.20.0
- 模型：本地 OpenAI 兼容 Mock（确定性哈希向量），用于验证工程链路，不代表真实模型回答质量
- 原则：以下只记录实际执行结果；未执行项单独列出，不把跳过当作通过。

## 1. 自动化检查

### AI 服务

```text
uv run ruff check .        All checks passed
uv run ruff format --check .  41 files already formatted
uv run pytest              31 passed, 1 skipped
```

跳过项：`test_qdrant_live.py` 默认需要 `CODEMIND_TEST_QDRANT_URL`。本机指定真实地址后单独执行：

```text
CODEMIND_TEST_QDRANT_URL=http://127.0.0.1:6333 uv run pytest tests/test_qdrant_live.py -q
. [100%]
```

覆盖：解析、切块、行号、Unicode/CRLF、语法失败降级、Embedding 批量与维度校验、Qdrant payload/过滤/计数、索引幂等与失败清理、问答引用和无效引用标记清理。

### Spring Boot

```text
./mvnw test
Tests run: 21, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
```

跳过项：`SchemaMigrationTest` 需要 Docker/Testcontainers。Flyway 已在独立真实 MySQL 上通过启动迁移验证。

### React

```text
npm run typecheck          通过
npm test                   2 passed
npm run build              成功（Vite 30 modules）
```

## 2. 基础设施实际状态

- MySQL：`127.0.0.1:3307` 启动成功，Flyway 7 张表迁移成功，JPA `validate` 启动通过。
- Qdrant：`1.20.0`，`/healthz` 返回 `healthz check passed`，仅监听 `127.0.0.1:6333`。
- AI：`/health/ready` 返回 `UP`，配置与 Qdrant 均为 `UP`。
- Spring Boot：`/actuator/health` 返回 `{"status":"UP"}`。
- React：`127.0.0.1:5173` 可访问。

## 3. 真实端到端结果

### 3.1 上传与自动索引

上传包含 1 个 Python 文件与 1 个 Java 文件的 ZIP，HTTP 返回：

```json
{"projectId":"97c55675-42e4-4e2f-95dd-81278d2271ed","sourceVersionId":"d03f922f-fe59-4cdd-abd0-84f9d02cd785","indexJobId":"1d440e65-4092-4cfc-882d-1cd9fd1a2a9d","status":"INDEXING"}
```

异步编排完成后：

```json
{"status":"READY","activeSourceVersionId":"d03f922f-fe59-4cdd-abd0-84f9d02cd785"}
```

索引任务：

```json
{"status":"SUCCEEDED","processedFiles":2,"sourceFileCount":2,"chunkCount":2,"attemptCount":1}
```

### 3.2 会话与问答

创建会话后提问“登录逻辑在哪里？”，返回：

```json
{
  "status": "COMPLETED",
  "sourceVersionId": "d03f922f-fe59-4cdd-abd0-84f9d02cd785",
  "citations": [
    {"citationIndex": 1, "filePath": "src/auth_service.py", "startLine": 1, "endLine": 13},
    {"citationIndex": 2, "filePath": "src/main/java/demo/OrderService.java", "startLine": 1, "endLine": 11}
  ]
}
```

历史查询返回 2 条消息、2 条引用；引用已写入 `message_citation`。

### 3.3 源码阅读

`GET /api/v1/source-versions/{versionId}/file` 读取 `src/auth_service.py` 第 8–12 行，返回内容与磁盘源码一致。

### 3.4 浏览器验收

实际在 Chromium 打开 `http://127.0.0.1:5173/`：

1. 首页显示真实隐私配置与最近项目。
2. 点击 READY 项目后成功创建会话。
3. 提交“登录逻辑在哪里？”，页面显示回答、`[1] src/auth_service.py:1` 和 `[2] src/main/java/demo/OrderService.java:1`。
4. 引用侧栏展示真实源码片段与分数。
5. 点击第一条引用，源码面板显示 `src/auth_service.py`、`L1–L13` 与完整真实文件内容。

## 4. 安全验收

此前真实上传测试已确认：

- 路径穿越 ZIP：HTTP 400 `UNSAFE_ARCHIVE_PATH`。
- 命中私钥样本：HTTP 422 `SENSITIVE_CONTENT_DETECTED`，错误响应未回显密钥内容。
- 上传临时目录在失败后清理。
- 浏览器请求不携带模型密钥；模型密钥只在 AI 服务端环境。
- 模型 Mock 收到的请求只有问题、检索片段或待索引代码块，不包含完整 ZIP 或宿主绝对路径。

## 5. 删除验收

对旧失败项目执行 `DELETE /api/v1/projects/{projectId}`：

```text
HTTP 204
再次 GET 项目：HTTP 404
MySQL project/source_version：0 行
Qdrant 按 project_id 精确计数：0
源码版本目录已删除
```

## 6. 未完成或未验收

- Docker Compose 未执行端到端启动：本机无 Docker。
- MySQL Testcontainers 用例跳过：同因无 Docker；但真实 MySQL 上 Flyway/JPA 已验收。
- 真实商业 Embedding/Chat 模型未调用：无可用密钥；工程链路使用本地 Mock 验证。真实模型质量、限流、费用与第三方保留政策需单独评估。
- BM25、RRF、Rerank、检索评估未实现，按计划留到 MVP 后续迭代。
- Qdrant 版本删除与索引的互斥为单进程锁，不含分布式锁或多实例协调。
- 删除 API 已通过上述接口级验收；本次浏览器演示只覆盖上传、问答与源码引用，未覆盖前端删除交互。
