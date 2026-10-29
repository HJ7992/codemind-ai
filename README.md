# CodeMind AI

CodeMind AI 是一个本地单用户的 Java/Python 代码库分析与问答工具。用户上传 ZIP 后，系统建立真实源码索引，并在回答中返回可核查的文件路径、代码片段和闭区间行号。

> 当前仓库处于**任务 1：设计冻结完成阶段**。前端、Spring Boot、FastAPI 业务功能和 Docker Compose 尚未实现，因此当前没有可启动的完整系统，也没有功能测试通过率或性能数据。

## 1. MVP 范围

- 上传并安全校验 UTF-8 Java/Python ZIP 项目。
- 异步解析、切块、生成 Embedding 并写入 Qdrant。
- 基于向量检索和外部 Chat API 回答单轮问题。
- 引用由系统从真实检索结果构造，不信任模型生成的路径或行号。
- 保存项目、源码版本、索引任务、会话、消息和引用快照。
- 不执行上传源码，不自动修改代码，不生成 PR。
- MVP 暂不包含登录、多租户、Git 导入、多轮问答、流式输出、BM25、RRF、Rerank、Redis 或消息队列。

## 2. 架构

```text
React ──公开 API──> Spring Boot ──内部凭证──> FastAPI ──> Qdrant
                      │  │                       │
                      │  └─> MySQL               ├─> 外部 Embedding API
                      └────> 受控源码卷 <─只读────┴─> 外部 Chat API
```

- 浏览器只访问 Spring Boot。
- Spring Boot 负责上传安全、隐私确认、MySQL 和任务编排。
- FastAPI 负责解析、Embedding、Qdrant、检索和受约束问答，不直接修改 MySQL。
- 外部模型不是项目状态或引用定位的权威来源。
- 本地单用户部署，不直接暴露公网；公网部署前必须另行加入认证、授权、限流和 HTTPS。

## 3. 唯一规范入口

实现前请按顺序阅读：

1. [MVP 设计冻结](docs/mvp-design-freeze.md)：范围、架构、安全、MySQL 与 Qdrant 约束。
2. [API 契约](docs/api/mvp-api-contract.md)：所有字段、JSON 示例、状态码、超时、重试和幂等。
3. [项目与索引任务状态机](docs/architecture/state-machine.md)：恢复、删除并发和版本切换。
4. [ADR 0001](docs/adr/0001-external-model-api-and-source-privacy.md)：模型选择、源码外发和敏感信息处理。
5. [测试验收清单](docs/testing/mvp-acceptance-checklist.md)：后续实现必须保留真实验收证据。

历史初始计划位于 `.hermes/plans/`，不再作为实现规范。

## 4. 启动前提（面向后续实现）

后续完整系统需要：

- Docker 与 Docker Compose；或分别安装 Java LTS/Maven、Python 3.12/uv、Node.js、MySQL 8.x 和 Qdrant。
- 可用的 OpenAI 兼容 Chat 与 Embedding 服务。两者可使用不同提供商。
- 本地未跟踪的环境配置文件，从 `deploy/env.example` 复制并填入真实值。
- 用户已阅读模型服务的数据保留政策，并同意必要代码块外发。

当前尚无 Compose 或服务实现，README 不提供未经验证的启动命令。任务 2/3 完成并实际运行后，应补充真实命令和输出。

## 5. 模型配置

| 配置 | 用途 |
|---|---|
| `CODEMIND_CHAT_BASE_URL` | Chat API 地址 |
| `CODEMIND_CHAT_MODEL` | Chat 模型名 |
| `CODEMIND_CHAT_API_KEY` | Chat 密钥，仅服务端 AI 容器可见 |
| `CODEMIND_EMBEDDING_BASE_URL` | Embedding API 地址 |
| `CODEMIND_EMBEDDING_MODEL` | Embedding 模型名 |
| `CODEMIND_EMBEDDING_API_KEY` | Embedding 密钥，仅服务端 AI 容器可见 |

完整占位符见 [`deploy/env.example`](deploy/env.example)。不要把真实密钥写入该示例、源码、前端、README、日志、测试报告或 Git。

模型异常必须明确失败，不能伪造成功或静默切换到未配置服务。CI 使用 Fake 和本地 Mock HTTP，不调用真实模型。

## 6. 源码隐私与安全

**索引期间的代码块会发送给配置的 Embedding 服务；问答期间的问题和检索代码片段会发送给配置的 Chat 服务。** 不只是 Chat 会接触源码。

系统禁止向模型发送完整 ZIP、整个项目目录、默认未选中的全部源码、宿主机绝对路径或已识别秘密。前端必须在上传前展示当前服务地址、发送数据类型、用途、第三方保留政策提示，并取得明确确认。

上传安全检查包括 ZIP 大小/数量/压缩比、路径穿越、绝对路径、盘符、UNC、加密包、链接、重复路径、文件类型和 UTF-8 编码。高置信度私钥/Token 在本地检测命中后拒绝上传并清理临时文件。

**本地检测无法保证识别所有秘密。** 用户仍须自行审查上传内容和第三方数据政策。

## 7. 后续开发顺序

1. 三服务最小骨架、健康检查、契约测试和 CI。
2. Compose、MySQL 迁移、Qdrant 和受控源码卷。
3. 上传安全、隐私确认和项目/任务持久化。
4. 解析切块、Embedding 和 Qdrant 索引。
5. 检索、受约束问答和 Spring 状态编排。
6. React 上传/问答/引用页面。
7. 真实端到端、安全和故障恢复验收。
8. MVP 通过后，再依次评估 BM25、RRF、Rerank 和检索评估。

每个阶段单独实现、运行和验收，不一次性生成整个项目。
