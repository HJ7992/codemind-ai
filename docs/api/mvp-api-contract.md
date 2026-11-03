# CodeMind AI MVP API 契约

- 状态：已冻结
- 日期：2026-10-29
- 上位规范：[MVP 设计冻结](../mvp-design-freeze.md)

## 1. 通用约定

### 1.1 数据格式

- 除上传 multipart 外，请求和响应使用 `application/json; charset=utf-8`。
- JSON 字段使用 camelCase；内部存储字段使用 snake_case，两者不得混用。
- 时间格式为 UTC ISO 8601，例如 `2026-10-29T04:00:00Z`。
- 所有资源 ID 和 `traceId` 为 UUID 字符串。
- 请求 DTO 默认 `additionalProperties=false`；未知字段返回 400，防止把公开问答变成自由模型代理。
- 公开 DTO、内部 DTO 和数据库实体分别维护。
- 空列表返回 `[]`，不以省略字段表示空列表。

### 1.2 路径和文本

- `path`、`filePath`、`relativePath` 为 UTF-8 规范化相对路径，统一 `/`；禁止绝对路径、盘符、UNC、`..`、NUL 和反斜杠。
- 行号从 1 开始，`endLine` 为闭区间；`1 <= startLine <= endLine`。
- 公开问题 `question` 去除首尾空白后长度为 1～4000 字符。
- 源码片段接口一次最多 500 行，且响应源码内容最大 100 KB；超出返回 400。
- `snippet`、`content`、模型 `answer` 可能包含源码；`storageKey` 仅为内部路径字段，不能出现在公开响应中。

### 1.3 统一错误

所有 4xx/5xx JSON 错误响应均为以下结构；Actuator 与 AI 健康检查是明确例外，只返回聚合 `status/checks`，不得借此暴露依赖细节：

| 字段 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `errorCode` | string | 是 | 稳定大写蛇形码，长度不超过 64 |
| `message` | string | 是 | 用户可理解的安全摘要，不含秘密、堆栈或宿主机路径 |
| `traceId` | string | 是 | 本次请求追踪 UUID |

```json
{
  "errorCode": "VALIDATION_ERROR",
  "message": "请求字段校验失败",
  "traceId": "5d2f11d0-09e0-4b70-93ef-e9db28995c01"
}
```

上游模型错误不得原样透传。后端与 AI 使用同一 `X-Trace-Id`；公开请求未提供时由 Spring Boot 生成。成功响应中的追踪 ID 通过 `X-Trace-Id` 响应头返回。下文 JSON 中的计数、分数和耗时仅用于展示数据格式，不是测试结果或性能指标。

### 1.4 内部身份校验

除 `/health/live`、`/health/ready` 外，所有 `/internal/v1` 请求必须同时满足：

- 仅来自内部 Docker 网络；不得发布到浏览器或公网。
- 请求头 `Authorization: Bearer <CODEMIND_INTERNAL_SERVICE_TOKEN>`。
- 请求头 `X-Trace-Id: <UUID>` 必填。
- 凭证缺失或错误返回 401，不区分用户不存在等细节。

健康检查不返回地址、模型名、密钥、源码或依赖详细配置。

### 1.5 超时、重试和幂等总表

| 操作 | 服务端超时上限 | 调用方重试 | 幂等约定 |
|---|---:|---|---|
| 上传校验 | 60 秒，不含浏览器上传传输时间 | 不自动重试 | 不保证幂等；重复提交可能创建新项目 |
| 内部索引 | 1800 秒 | 不立即重试；由任务恢复串行重建 | 对未活动版本可重建；确定性 point ID；活动版本拒绝 |
| 公开/内部问答 | 65/60 秒 | 不自动重试 | 不保证幂等；重复请求可能重复计费和产生消息 |
| 内部检索 | 15 秒 | 安全重试最多 1 次 | 只读 |
| 删除项目/版本 | 单次内部调用 30 秒 | 删除编排可重试 | 重复删除返回相同终态语义 |
| 创建会话 | 10 秒 | 不自动重试 | 不保证幂等 |
| GET 查询 | 10 秒 | 网络失败可重试 1 次 | 只读 |
| Chat 单次调用 | 45 秒 | 不自动重试 | 避免重复费用 |
| Embedding 单次批量 | 30 秒 | 不自动重试 | 由索引任务决定是否重建 |

连接失败、429、5xx 均不绕过上述模型重试约束。超时不能当作对端未执行，尤其禁止并发重复索引。

## 2. 公共数据结构

### 2.1 `Citation`

| 字段 | 类型 | 必填 | 校验/来源 |
|---|---|---|---|
| `citationIndex` | integer | 是 | 从 1 开始，在当前回答中唯一且连续 |
| `filePath` | string | 是 | 真实检索 payload 的规范化相对路径 |
| `startLine`、`endLine` | integer | 是 | 原始文件闭区间行号 |
| `snippet` | string | 是 | 系统回读/校验的真实源码，不由模型生成 |
| `score` | number/null | 是 | 原始向量分数，不解释为百分比 |

```json
{
  "citationIndex": 1,
  "filePath": "src/main/java/demo/LoginService.java",
  "startLine": 18,
  "endLine": 24,
  "snippet": "public boolean login(String user, String password) {\n    return repository.matches(user, password);\n}",
  "score": 0.8123
}
```

### 2.2 `RetrievalMetadata`

| 字段 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `strategy` | string | 是 | MVP 固定 `VECTOR` |
| `topK` | integer | 是 | 服务端配置，范围 1～20 |
| `chatProvider` | string | 是 | 非敏感提供商标识；无 Chat 调用时可为 `NONE` |
| `chatModel` | string/null | 是 | 无 Chat 调用时为 null |
| `embeddingModel` | string | 是 | 当前索引/检索模型 |
| `retrievalDurationMs` | integer | 是 | 大于等于 0 |
| `generationDurationMs` | integer | 是 | 未生成时为 0 |

### 2.3 `Message`

| 字段 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `messageId` | string UUID | 是 | 消息 ID |
| `role` | string | 是 | `USER` 或 `ASSISTANT` |
| `content` | string | 是 | 用户问题、回答或安全失败占位文本；可能含源码 |
| `status` | string | 是 | `COMPLETED` 或 `FAILED` |
| `sourceVersionId` | string UUID/null | 是 | 用户消息可为 null；助手消息记录实际版本 |
| `errorCode` | string/null | 是 | 失败时有值，成功时为 null |
| `citations` | Citation[] | 是 | 用户消息为空数组 |
| `createdAt` | string datetime | 是 | UTC |

## 3. 浏览器 → Spring Boot

公开接口在本地单用户边界内不设置用户登录，但只绑定本机/受信地址。任何字段都不能携带模型 API 密钥。

### 3.1 获取上传前隐私告知

`GET /api/v1/privacy-notice`

用途：让前端在上传前读取服务端真实模型地址和告知版本。此接口是为满足隐私确认而新增的唯一公开辅助接口。

请求：无请求体、无查询参数。

响应字段：

| 字段 | 类型 | 必填 | 校验 |
|---|---|---|---|
| `noticeVersion` | string | 是 | 非空，配置变化时应更新 |
| `embedding.baseUrl` | string URI | 是 | 当前 Embedding 地址，剥离用户信息/query/fragment |
| `embedding.dataTypes` | string[] | 是 | 明确包含代码块 |
| `chat.baseUrl` | string URI | 是 | 当前 Chat 地址，同样脱敏 |
| `chat.dataTypes` | string[] | 是 | 明确包含问题与检索代码片段 |
| `thirdPartyRetentionNotice` | string | 是 | 提示用户确认第三方政策 |
| `secretDetectionLimitation` | string | 是 | 明确非 100% 检测 |

成功 `200`：

```json
{
  "noticeVersion": "2026-10-29.v1",
  "embedding": {
    "baseUrl": "https://embedding-provider.example/v1",
    "purpose": "将索引代码块转换为向量",
    "dataTypes": ["JAVA_OR_PYTHON_CODE_CHUNK"]
  },
  "chat": {
    "baseUrl": "https://chat-provider.example/v1",
    "purpose": "根据用户问题和检索代码片段生成回答",
    "dataTypes": ["USER_QUESTION", "RETRIEVED_CODE_CHUNK"]
  },
  "thirdPartyRetentionNotice": "代码文本将发送至上述第三方，请自行确认其数据保留政策。",
  "secretDetectionLimitation": "本地检测无法保证识别所有秘密，上传前请自行审查。"
}
```

失败 `503`：

```json
{
  "errorCode": "MODEL_CONFIGURATION_INVALID",
  "message": "模型服务配置不完整，无法生成隐私告知",
  "traceId": "5d2f11d0-09e0-4b70-93ef-e9db28995c02"
}
```

其他状态码：`500 INTERNAL_ERROR`。超时/重试按 GET 查询；响应和日志禁止包含 API 密钥，地址不得含凭证。

### 3.2 上传项目

`POST /api/v1/projects/upload`

请求：`multipart/form-data`。

| 字段 | 类型 | 必填 | 校验 |
|---|---|---|---|
| `file` | binary ZIP | 是 | 最大 50 MB，扩展名与 ZIP 内容均校验 |
| `noticeVersion` | string | 是 | 必须等于当前告知版本 |
| `externalTransferAccepted` | boolean | 是 | 必须为 true，明确允许 Embedding/Chat 代码外发 |

成功 `202`：表示安全校验和持久化完成、索引已接受，不表示 READY。

```json
{
  "projectId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e101",
  "sourceVersionId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e102",
  "indexJobId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e103",
  "status": "INDEXING"
}
```

| 响应字段 | 类型 | 必填 |
|---|---|---|
| `projectId`、`sourceVersionId`、`indexJobId` | string UUID | 是 |
| `status` | string | 是，固定 `INDEXING` |

失败示例 `422`：

```json
{
  "errorCode": "SENSITIVE_CONTENT_DETECTED",
  "message": "检测到高风险敏感信息，上传已拒绝并清理",
  "traceId": "5d2f11d0-09e0-4b70-93ef-e9db28995c03"
}
```

状态码：`400` 字段/路径/确认错误，`401` 不适用，`409` 重复规范化路径，`413` 大小/条目/压缩比超限，`415` 非 ZIP，`422` 编码、秘密、加密/链接等安全校验失败，`500` 内部错误，`503` 模型配置不可用。

请求含完整 ZIP，只允许 Spring Boot 本地处理；禁止转发给模型。响应不含源码。不自动重试，60 秒校验上限。

### 3.3 查询项目

`GET /api/v1/projects/{projectId}`

路径参数：`projectId`，必填 UUID。无请求体。

响应字段：

| 字段 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `projectId` | string UUID | 是 | 路径对应项目 |
| `name` | string | 是 | 安全展示名 |
| `status` | string | 是 | 四种项目状态之一 |
| `activeSourceVersionId` | string UUID/null | 是 | 仅 READY 时非空 |
| `indexJobId` | string UUID | 是 | 关联任务 |
| `errorCode` | string/null | 是 | 失败/删除部分失败时的安全错误码 |
| `createdAt`、`updatedAt` | string datetime | 是 | UTC |

成功 `200`：

```json
{
  "projectId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e101",
  "name": "demo-project",
  "status": "READY",
  "activeSourceVersionId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e102",
  "indexJobId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e103",
  "errorCode": null,
  "createdAt": "2026-10-29T04:00:00Z",
  "updatedAt": "2026-10-29T04:01:00Z"
}
```

失败 `404`：

```json
{
  "errorCode": "PROJECT_NOT_FOUND",
  "message": "项目不存在或已删除",
  "traceId": "5d2f11d0-09e0-4b70-93ef-e9db28995c04"
}
```

其他状态码：`400` UUID 错误，`500` 内部错误。只读，不含源码、storageKey 或密钥。

### 3.4 删除项目

`DELETE /api/v1/projects/{projectId}`

路径参数：`projectId`，必填 UUID。无请求体。首次或删除中重复请求成功 `202`：

```json
{
  "projectId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e101",
  "status": "DELETING"
}
```

响应字段：`projectId` 为 UUID，`status` 固定 `DELETING`，均必填。全部删除后的重复请求返回 `204`，无响应体。

失败 `500` 示例（仅表示删除请求未能接受；已接受后的异步清理失败通过项目查询中的 `errorCode=PROJECT_DELETE_RETRYABLE` 暴露）：

```json
{
  "errorCode": "INTERNAL_ERROR",
  "message": "删除请求未能接受，请稍后重试",
  "traceId": "5d2f11d0-09e0-4b70-93ef-e9db28995c05"
}
```

状态码：`202` 接受/处理中，`204` 已不存在，`400` ID 错误，`500/503` 可恢复清理失败。幂等；不返回源码或绝对路径。删除规则见状态机文档。

### 3.5 查询索引任务

`GET /api/v1/index-jobs/{jobId}`

路径参数：`jobId`，必填 UUID。无请求体。

| 响应字段 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `jobId`、`projectId`、`sourceVersionId` | string UUID | 是 | 必须互相对应 |
| `status` | string | 是 | 四种任务状态之一 |
| `processedFiles` | integer | 是 | 0～sourceFileCount |
| `sourceFileCount` | integer | 是 | 大于等于 0 |
| `chunkCount` | integer | 是 | 大于等于 0 |
| `attemptCount`、`maxAttempts` | integer | 是 | `0 <= attemptCount <= maxAttempts` |
| `errorCode`、`errorMessage` | string/null | 是 | 脱敏，成功时为 null |
| `startedAt`、`finishedAt` | datetime/null | 是 | 依状态可空 |
| `createdAt`、`updatedAt` | datetime | 是 | UTC |

成功 `200`：

```json
{
  "jobId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e103",
  "projectId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e101",
  "sourceVersionId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e102",
  "status": "RUNNING",
  "processedFiles": 8,
  "sourceFileCount": 20,
  "chunkCount": 0,
  "attemptCount": 1,
  "maxAttempts": 2,
  "errorCode": null,
  "errorMessage": null,
  "startedAt": "2026-10-29T04:00:10Z",
  "finishedAt": null,
  "createdAt": "2026-10-29T04:00:00Z",
  "updatedAt": "2026-10-29T04:00:20Z"
}
```

失败 `404`：

```json
{
  "errorCode": "INDEX_JOB_NOT_FOUND",
  "message": "索引任务不存在",
  "traceId": "5d2f11d0-09e0-4b70-93ef-e9db28995c06"
}
```

其他状态码：`400`、`500`。只读；错误字段不能含源码、密钥或上游原始响应。

### 3.6 创建会话

`POST /api/v1/conversations`

请求字段：

| 字段 | 类型 | 必填 | 校验 |
|---|---|---|---|
| `projectId` | string UUID | 是 | 项目必须 READY |
| `title` | string/null | 否 | 最大 255 字符，去除控制字符 |

```json
{
  "projectId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e101",
  "title": "登录流程分析"
}
```

成功 `201`：

```json
{
  "conversationId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e104",
  "projectId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e101",
  "title": "登录流程分析",
  "createdAt": "2026-10-29T04:02:00Z"
}
```

响应字段均为对应请求/ID/时间类型，`conversationId` 必填 UUID，`createdAt` 必填 UTC。

失败 `409`：

```json
{
  "errorCode": "PROJECT_NOT_READY",
  "message": "项目尚未完成索引，不能创建问答会话",
  "traceId": "5d2f11d0-09e0-4b70-93ef-e9db28995c07"
}
```

状态码：`201`、`400`、`404 PROJECT_NOT_FOUND`、`409 PROJECT_NOT_READY`、`500`。10 秒超时，不自动重试，不保证创建幂等；请求标题不得含秘密，响应不含源码。

### 3.7 查询会话

`GET /api/v1/conversations/{conversationId}`

路径参数：`conversationId`，必填 UUID。无请求体。

| 响应字段 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `conversationId`、`projectId` | string UUID | 是 | 会话归属 |
| `title` | string/null | 是 | 展示标题 |
| `messages` | Message[] | 是 | 按 createdAt 升序 |
| `createdAt`、`updatedAt` | datetime | 是 | UTC |

成功 `200`：

```json
{
  "conversationId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e104",
  "projectId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e101",
  "title": "登录流程分析",
  "messages": [
    {
      "messageId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e105",
      "role": "USER",
      "content": "登录校验在哪里？",
      "status": "COMPLETED",
      "sourceVersionId": null,
      "errorCode": null,
      "citations": [],
      "createdAt": "2026-10-29T04:02:10Z"
    }
  ],
  "createdAt": "2026-10-29T04:02:00Z",
  "updatedAt": "2026-10-29T04:02:10Z"
}
```

失败 `404`：

```json
{
  "errorCode": "CONVERSATION_NOT_FOUND",
  "message": "会话不存在",
  "traceId": "5d2f11d0-09e0-4b70-93ef-e9db28995c08"
}
```

其他状态码：`400`、`500`。只读；`content/snippet` 可含源码，但不含密钥字段。失败消息不能以 `COMPLETED` 返回。

### 3.8 提问

`POST /api/v1/conversations/{conversationId}/questions`

路径参数：`conversationId`，必填 UUID。

请求只有以下字段，禁止 `model`、`topK`、`strategy`、baseUrl 等额外字段：

| 字段 | 类型 | 必填 | 校验 |
|---|---|---|---|
| `question` | string | 是 | 去空白后 1～4000 字符；会发送给 Chat（有证据时） |

```json
{
  "question": "登录校验在哪里？"
}
```

响应字段：

| 字段 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `messageId` | string UUID | 是 | 成功的助手消息 |
| `answer` | string | 是 | 模型回答或无证据说明；可能含源码 |
| `status` | string | 是 | 成功固定 `COMPLETED` |
| `sourceVersionId` | string UUID | 是 | 本次活动版本 |
| `citations` | Citation[] | 是 | 无证据时为空 |
| `retrievalMetadata` | RetrievalMetadata | 是 | 不含密钥或完整请求 |

成功 `200`：

```json
{
  "messageId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e106",
  "answer": "登录校验位于 LoginService.login，由 repository.matches 完成。[1]",
  "status": "COMPLETED",
  "sourceVersionId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e102",
  "citations": [
    {
      "citationIndex": 1,
      "filePath": "src/main/java/demo/LoginService.java",
      "startLine": 18,
      "endLine": 24,
      "snippet": "public boolean login(String user, String password) {\n    return repository.matches(user, password);\n}",
      "score": 0.8123
    }
  ],
  "retrievalMetadata": {
    "strategy": "VECTOR",
    "topK": 5,
    "chatProvider": "CONFIGURED_OPENAI_COMPATIBLE",
    "chatModel": "chat-model-placeholder",
    "embeddingModel": "embedding-model-placeholder",
    "retrievalDurationMs": 35,
    "generationDurationMs": 820
  }
}
```

失败 `502`：

```json
{
  "errorCode": "CHAT_UPSTREAM_ERROR",
  "message": "回答生成服务暂时不可用",
  "traceId": "5d2f11d0-09e0-4b70-93ef-e9db28995c09"
}
```

状态码：`200`；`400` 问题/字段错误；`404` 会话不存在；`409` 项目非 READY/正在删除；`502` Chat/Qdrant 上游错误；`504` 问答超时；`500` 内部错误。

65 秒上限，不自动重试，不保证幂等。失败可持久化 `FAILED` 助手消息，但当前 POST 必须返回非 2xx，不能把失败回答显示为成功。

### 3.9 查看源码

`GET /api/v1/source-versions/{versionId}/file?path={path}&startLine={n}&endLine={n}`

| 参数 | 位置 | 类型 | 必填 | 校验 |
|---|---|---|---|---|
| `versionId` | path | string UUID | 是 | 版本存在且未删除 |
| `path` | query | string | 是 | 该版本内规范化相对源码路径 |
| `startLine`、`endLine` | query | integer | 是 | 合法闭区间，最多 500 行/100 KB |

无请求体。响应字段：

| 字段 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `sourceVersionId` | string UUID | 是 | 与路径一致 |
| `path` | string | 是 | 真实文件相对路径 |
| `startLine`、`endLine` | integer | 是 | 实际返回范围 |
| `content` | string | 是 | 真实源码片段 |

成功 `200`：

```json
{
  "sourceVersionId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e102",
  "path": "src/main/java/demo/LoginService.java",
  "startLine": 18,
  "endLine": 20,
  "content": "public boolean login(String user, String password) {\n    return repository.matches(user, password);\n}"
}
```

失败 `400`：

```json
{
  "errorCode": "SOURCE_LINE_RANGE_INVALID",
  "message": "源码行号范围无效或超过读取限制",
  "traceId": "5d2f11d0-09e0-4b70-93ef-e9db28995c10"
}
```

状态码：`200`；`400` 路径/范围错误；`404 SOURCE_VERSION_NOT_FOUND` 或 `SOURCE_FILE_NOT_FOUND`；`409 PROJECT_DELETING`；`500`。只读；必须校验版本、路径、文件归属和行号，禁止任意文件读取。

### 3.10 Spring Boot 健康检查

`GET /actuator/health`

无请求体、无参数。成功 `200`：

```json
{
  "status": "UP"
}
```

依赖不可用 `503`：

```json
{
  "status": "DOWN"
}
```

响应只有聚合状态，不公开 MySQL/Qdrant/AI 地址、凭据或模型配置。容器健康检查不自动重试业务写操作。

## 4. Spring Boot → FastAPI

以下请求均需内部身份校验（健康检查除外）。示例不展示真实 Authorization。除明确标注外，错误结构遵循第 1.3 节。

### 4.1 建立索引

`POST /internal/v1/indexes`

请求字段：

| 字段 | 类型 | 必填 | 校验/敏感性 |
|---|---|---|---|
| `jobId`、`projectId`、`sourceVersionId` | string UUID | 是 | 必须与任务匹配 |
| `storageKey` | string | 是 | 规范化相对存储键；禁止绝对路径和 `..`，不进入外部模型请求 |

```json
{
  "jobId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e103",
  "projectId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e101",
  "sourceVersionId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e102",
  "storageKey": "projects/8f79ab6c-e2c4-4b4e-b410-e3cb93d9e101/versions/8f79ab6c-e2c4-4b4e-b410-e3cb93d9e102"
}
```

响应字段：

| 字段 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `jobId`、`projectId`、`sourceVersionId` | string UUID | 是 | 与请求完全一致 |
| `status` | string | 是 | 成功固定 `SUCCEEDED` |
| `sourceFileCount` | integer | 是 | 大于 0 |
| `chunkCount` | integer | 是 | 大于 0，且已通过 Qdrant 点数校验 |
| `embeddingModel` | string | 是 | 实际模型名，不是密钥 |

成功 `200`：

```json
{
  "jobId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e103",
  "projectId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e101",
  "sourceVersionId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e102",
  "status": "SUCCEEDED",
  "sourceFileCount": 20,
  "chunkCount": 86,
  "embeddingModel": "embedding-model-placeholder"
}
```

失败 `502`：

```json
{
  "errorCode": "EMBEDDING_UPSTREAM_ERROR",
  "message": "向量生成服务调用失败，索引未完成",
  "traceId": "5d2f11d0-09e0-4b70-93ef-e9db28995c11"
}
```

状态码：`200`；`400` 路径/字段错误；`401` 服务认证失败；`404` 源码版本目录不存在；`409` 同版本正在索引或尝试重建活动版本；`410 SOURCE_VERSION_DELETED`；`422` 编码/解析无法形成有效索引；`502` Embedding/Qdrant；`504` 索引超时；`500`。

1800 秒上限。仅未活动版本可串行重建；模型请求只含有限代码块，不含 storageKey、ZIP 或完整项目。成功响应不含源码。

### 4.2 内部问答

`POST /internal/v1/qa`

请求字段：

| 字段 | 类型 | 必填 | 校验/敏感性 |
|---|---|---|---|
| `projectId`、`sourceVersionId` | string UUID | 是 | 双过滤，版本由 Spring 从活动版本取得 |
| `question` | string | 是 | 1～4000 字符；有证据时允许发送给 Chat |

禁止调用方指定模型、topK、策略和地址。

```json
{
  "projectId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e101",
  "sourceVersionId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e102",
  "question": "登录校验在哪里？"
}
```

响应字段与公开问答的 `answer`、`citations`、`retrievalMetadata` 相同，并额外返回 `sourceVersionId`；全部必填。成功 `200`：

```json
{
  "sourceVersionId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e102",
  "answer": "登录校验位于 LoginService.login。[1]",
  "citations": [
    {
      "citationIndex": 1,
      "filePath": "src/main/java/demo/LoginService.java",
      "startLine": 18,
      "endLine": 24,
      "snippet": "public boolean login(String user, String password) {\n    return repository.matches(user, password);\n}",
      "score": 0.8123
    }
  ],
  "retrievalMetadata": {
    "strategy": "VECTOR",
    "topK": 5,
    "chatProvider": "CONFIGURED_OPENAI_COMPATIBLE",
    "chatModel": "chat-model-placeholder",
    "embeddingModel": "embedding-model-placeholder",
    "retrievalDurationMs": 35,
    "generationDurationMs": 820
  }
}
```

无足够证据仍为 `200`：`answer` 明确说明证据不足、`citations=[]`、`chatModel=null`、`chatProvider=NONE`、`generationDurationMs=0`，且不得调用 Chat。

失败 `504`：

```json
{
  "errorCode": "CHAT_UPSTREAM_TIMEOUT",
  "message": "回答生成超时",
  "traceId": "5d2f11d0-09e0-4b70-93ef-e9db28995c12"
}
```

状态码：`200`；`400`；`401`；`404` 版本无可用索引；`409` 版本删除中；`502` Chat/Qdrant；`504`；`500`。60 秒上限，不自动重试。Chat 只接收问题和选中片段；响应引用可含源码，但错误不得含源码或上游正文。

### 4.3 内部检索

`POST /internal/v1/retrievals`

请求字段：

| 字段 | 类型 | 必填 | 校验 |
|---|---|---|---|
| `projectId`、`sourceVersionId` | string UUID | 是 | 强制双过滤 |
| `query` | string | 是 | 去空白后 1～4000 字符 |
| `topK` | integer | 否 | 1～20；缺省使用服务端配置，仅内部调试/评估开放 |

```json
{
  "projectId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e101",
  "sourceVersionId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e102",
  "query": "登录密码校验",
  "topK": 5
}
```

响应：`sourceVersionId`、`strategy=VECTOR`、`chunks` 必填。每个 chunk 的 `chunkId`、`filePath`、`startLine`、`endLine`、`snippet`、`score` 必填，类型分别为 string/string/integer/integer/string/number。

成功 `200`：

```json
{
  "sourceVersionId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e102",
  "strategy": "VECTOR",
  "chunks": [
    {
      "chunkId": "01b2fd05-e147-4a5e-9f77-e8fd0f6a9e01",
      "filePath": "src/main/java/demo/LoginService.java",
      "startLine": 18,
      "endLine": 24,
      "snippet": "public boolean login(String user, String password) {\n    return repository.matches(user, password);\n}",
      "score": 0.8123
    }
  ]
}
```

失败 `404`：

```json
{
  "errorCode": "SOURCE_INDEX_NOT_FOUND",
  "message": "指定源码版本没有可用索引",
  "traceId": "5d2f11d0-09e0-4b70-93ef-e9db28995c13"
}
```

状态码：`200`（包括空 chunks）、`400`、`401`、`404`、`409`、`502` Qdrant、`504`、`500`。15 秒，只读，可安全重试一次。响应 snippet 含源码，只能在内部网络传输，不记录到普通日志。

### 4.4 删除源码版本索引

`DELETE /internal/v1/indexes/{sourceVersionId}?projectId={projectId}`

| 参数 | 类型 | 必填 | 校验 |
|---|---|---|---|
| `sourceVersionId` | path UUID | 是 | 待删除版本 |
| `projectId` | query UUID | 是 | 防止误删其他项目，必须与版本归属一致 |

无请求体。响应字段：`sourceVersionId`、`projectId` UUID，`status=DELETED`，`deletedChunkCount` 大于等于 0，均必填。

成功 `200`（首次或重复删除）：

```json
{
  "sourceVersionId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e102",
  "projectId": "8f79ab6c-e2c4-4b4e-b410-e3cb93d9e101",
  "status": "DELETED",
  "deletedChunkCount": 86
}
```

失败 `409`：

```json
{
  "errorCode": "INDEX_VERSION_CONFLICT",
  "message": "源码版本与项目归属不一致",
  "traceId": "5d2f11d0-09e0-4b70-93ef-e9db28995c14"
}
```

状态码：`200`；`400` 参数错误；`401`；`409` 归属冲突；`502` Qdrant 删除失败；`504`；`500`。30 秒单次上限，编排可重试，重复删除幂等。无源码字段；必须写删除墓碑并与索引串行化。

### 4.5 AI 存活检查

`GET /health/live`

无请求体、无服务凭证；只允许内部网络。进程可响应即 `200`：

```json
{
  "status": "UP"
}
```

该接口设计上不在进程可正常响应时返回业务依赖错误；无法响应由容器健康检查判定失败。若能生成错误响应，使用 `503`：

```json
{
  "status": "DOWN"
}
```

不检查外部模型，不返回配置或源码，不执行重试。

### 4.6 AI 就绪检查

`GET /health/ready`

无请求体、无服务凭证；只允许内部网络。检查必要配置格式和 Qdrant 可用性，不调用付费模型。

成功 `200`：

```json
{
  "status": "UP",
  "checks": {
    "configuration": "UP",
    "qdrant": "UP"
  }
}
```

失败 `503`：

```json
{
  "status": "DOWN",
  "checks": {
    "configuration": "UP",
    "qdrant": "DOWN"
  }
}
```

`checks` 只允许上述聚合键和 `UP/DOWN`，不得返回 baseUrl、模型名、密钥、异常正文或源码。不就绪时 Spring 不得提交新索引/问答。

## 5. 错误码最小集合

实现可补充更具体错误码，但必须同步更新本文和测试，不能只在代码中隐式增加。

| 类别 | 错误码示例 |
|---|---|
| 通用 | `VALIDATION_ERROR`、`INTERNAL_ERROR`、`UPSTREAM_TIMEOUT` |
| 隐私 | `PRIVACY_NOTICE_REQUIRED`、`PRIVACY_NOTICE_VERSION_MISMATCH`、`MODEL_CONFIGURATION_INVALID` |
| 上传 | `UPLOAD_LIMIT_EXCEEDED`、`EMPTY_UPLOAD`、`INVALID_ZIP_ARCHIVE`、`UNSAFE_ARCHIVE_PATH`、`UNSAFE_STORAGE_PATH`、`DUPLICATE_ARCHIVE_PATH`、`UNSUPPORTED_ENCODING`、`SENSITIVE_CONTENT_DETECTED`、`ENCRYPTED_ZIP_REJECTED`、`SYMLINK_REJECTED`、`NO_SOURCE_FILES`、`SOURCE_STORAGE_ERROR` |
| 项目/任务 | `PROJECT_NOT_FOUND`、`PROJECT_NOT_READY`、`PROJECT_DELETING`、`INDEX_JOB_NOT_FOUND` |
| 索引 | `SOURCE_VERSION_DELETED`、`INDEX_ALREADY_RUNNING`、`INDEX_VERSION_ACTIVE`、`EMBEDDING_UPSTREAM_ERROR`、`QDRANT_UPSTREAM_ERROR` |
| 问答 | `SOURCE_INDEX_NOT_FOUND`、`CHAT_UPSTREAM_ERROR`、`CHAT_UPSTREAM_TIMEOUT` |
| 源码 | `SOURCE_VERSION_NOT_FOUND`、`SOURCE_FILE_NOT_FOUND`、`SOURCE_LINE_RANGE_INVALID` |
| 删除 | `INDEX_VERSION_CONFLICT`、`PROJECT_DELETE_RETRYABLE` |

所有错误码都必须经过日志脱敏测试；HTTP 状态与错误码的对应关系以各接口章节为准。
