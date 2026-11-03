import { useEffect, useState } from "react";
import {
  askQuestion,
  createConversation,
  getConversation,
  getPrivacyNotice,
  getProject,
  listProjects,
  readSource,
  uploadProject,
  type Citation,
  type Conversation,
  type Message,
  type PrivacyNotice,
  type Project,
  type SourceExcerpt,
} from "./api";

const sleep = (milliseconds: number) =>
  new Promise((resolve) => window.setTimeout(resolve, milliseconds));

const statusText: Record<Project["status"], string> = {
  INDEXING: "正在索引",
  READY: "可以提问",
  INDEX_FAILED: "索引失败",
  DELETING: "正在删除",
};

export default function App() {
  const [notice, setNotice] = useState<PrivacyNotice | null>(null);
  const [projects, setProjects] = useState<Project[]>([]);
  const [file, setFile] = useState<File | null>(null);
  const [accepted, setAccepted] = useState(false);
  const [project, setProject] = useState<Project | null>(null);
  const [conversation, setConversation] = useState<Conversation | null>(null);
  const [question, setQuestion] = useState("");
  const [preview, setPreview] = useState<SourceExcerpt | null>(null);
  const [busy, setBusy] = useState(false);
  const [loadingText, setLoadingText] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    Promise.all([getPrivacyNotice(), listProjects()])
      .then(([privacy, recent]) => {
        setNotice(privacy);
        setProjects(recent);
      })
      .catch((reason: Error) => setError(reason.message));
  }, []);

  async function handleUpload(event: React.FormEvent) {
    event.preventDefault();
    if (!file || !notice || !accepted) {
      setError("请选择 ZIP 文件并确认源码外发告知。");
      return;
    }
    setBusy(true);
    setError("");
    setPreview(null);
    try {
      setLoadingText("正在安全解包并创建索引任务…");
      const acceptedUpload = await uploadProject(file, notice.noticeVersion, true);
      const latest = await waitForIndex(acceptedUpload.projectId);
      if (latest.status !== "READY") {
        throw new Error(latest.errorCode === "AI_INDEXING_ERROR"
          ? "索引失败：AI 服务未能完成向量化。"
          : `索引失败：${latest.errorCode ?? "未知错误"}`);
      }
      setProject(latest);
      await openConversation(latest);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "上传失败");
    } finally {
      setBusy(false);
      setLoadingText("");
    }
  }

  async function waitForIndex(projectId: string): Promise<Project> {
    for (let attempt = 0; attempt < 60; attempt += 1) {
      const latest = await getProject(projectId);
      setProject(latest);
      if (latest.status !== "INDEXING") {
        return latest;
      }
      setLoadingText(
        attempt < 8 ? "正在解析源码结构并生成向量…" : "索引仍在进行，请稍候…",
      );
      await sleep(1000);
    }
    throw new Error("索引等待超时，请稍后刷新项目状态。");
  }

  async function openProject(selected: Project) {
    if (selected.status !== "READY") {
      return;
    }
    setBusy(true);
    setError("");
    setProject(selected);
    try {
      await openConversation(selected);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "无法创建会话");
    } finally {
      setBusy(false);
    }
  }

  async function openConversation(target: Project) {
    setLoadingText("正在创建分析会话…");
    const created = await createConversation(target.projectId);
    const loaded = await getConversation(created.conversationId);
    setConversation(loaded);
  }

  async function ask(event: React.FormEvent) {
    event.preventDefault();
    const trimmed = question.trim();
    if (!trimmed || !conversation || busy) {
      return;
    }
    setBusy(true);
    setError("");
    try {
      await askQuestion(conversation.conversationId, trimmed);
      setConversation(await getConversation(conversation.conversationId));
      setQuestion("");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "提问失败");
    } finally {
      setBusy(false);
    }
  }

  async function showSource(citation: Citation) {
    if (!project?.activeSourceVersionId) {
      return;
    }
    setError("");
    try {
      setPreview(
        await readSource(
          project.activeSourceVersionId,
          citation.filePath,
          citation.startLine,
          citation.endLine,
        ),
      );
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "读取源码失败");
    }
  }

  function reset() {
    setProject(null);
    setConversation(null);
    setPreview(null);
    setFile(null);
    setAccepted(false);
    setError("");
  }

  return (
    <div className="page">
      <header className="topbar">
        <button className="brand" onClick={reset} type="button" aria-label="返回首页">
          <span className="brand-mark">CM</span>
          <span>
            <strong>CodeMind AI</strong>
            <small>源码级 AI 项目助手</small>
          </span>
        </button>
        <div className="runtime-pill"><span className="pulse" /> 本地私有部署</div>
      </header>

      {error && (
        <div className="alert" role="alert">
          {error}
          <button type="button" onClick={() => setError("")}>关闭</button>
        </div>
      )}

      {!project ? (
        <main className="landing">
          <section className="hero">
            <p className="eyebrow">READ · RETRIEVE · CITE</p>
            <h1>让 AI 读懂你的<br /><em>整个代码项目</em></h1>
            <p className="hero-copy">
              上传 Java / Python 项目 ZIP。系统解析真实源码结构，建立向量索引，
              回答时给出文件路径、行号和可点击的源码片段。
            </p>
            <div className="capabilities">
              <span>Java + Python</span><span>向量检索</span><span>可核查引用</span>
            </div>
          </section>

          <section className="upload-card">
            <div className="step-label">01 / 上传项目</div>
            <h2>开始分析你的代码库</h2>
            <form onSubmit={handleUpload}>
              <label className={`dropzone ${file ? "has-file" : ""}`}>
                <input
                  type="file"
                  accept=".zip,application/zip"
                  onChange={(event) => setFile(event.target.files?.[0] ?? null)}
                  disabled={busy}
                />
                <span className="upload-icon">↑</span>
                <strong>{file ? file.name : "选择 ZIP 文件"}</strong>
                <small>{file ? `${(file.size / 1024).toFixed(1)} KB · 点击可更换` : "最大 50 MB，仅索引 .java 与 .py"}</small>
              </label>
              {notice && (
                <div className="privacy-box">
                  <div className="privacy-title">源码外发告知</div>
                  <p>
                    索引时的代码块会发送到 <code>{notice.embedding.baseUrl}</code> 生成向量；
                    提问时检索到的片段会发送到 <code>{notice.chat.baseUrl}</code>。
                  </p>
                  <p className="muted">{notice.thirdPartyRetentionNotice}</p>
                  <p className="muted">{notice.secretDetectionLimitation}</p>
                </div>
              )}
              <label className="check-row">
                <input
                  type="checkbox"
                  checked={accepted}
                  onChange={(event) => setAccepted(event.target.checked)}
                  disabled={busy}
                />
                <span>我已阅读并同意上述源码数据处理方式</span>
              </label>
              <button className="primary" type="submit" disabled={busy || !file || !accepted}>
                {busy ? (loadingText || "处理中…") : "上传并建立索引 →"}
              </button>
            </form>
          </section>

          {projects.length > 0 && (
            <section className="recent">
              <div className="section-heading">
                <div><div className="step-label">最近项目</div><h2>继续上次分析</h2></div>
              </div>
              <div className="project-grid">
                {projects.map((item) => (
                  <button
                    className="project-card"
                    key={item.projectId}
                    onClick={() => openProject(item)}
                    disabled={item.status !== "READY"}
                    type="button"
                  >
                    <span className={`status-dot ${item.status.toLowerCase()}`} />
                    <span className="project-name">{item.name}</span>
                    <span className="project-meta">{statusText[item.status]}</span>
                    <span className="arrow">↗</span>
                  </button>
                ))}
              </div>
            </section>
          )}
        </main>
      ) : (
        <main className="workspace">
          <section className="workspace-head">
            <div>
              <button className="text-button" type="button" onClick={reset}>← 全部项目</button>
              <h1>{project.name}</h1>
              <div className="project-subline">
                <span className={`status-dot ${project.status.toLowerCase()}`} />
                {statusText[project.status]}
                {project.activeSourceVersionId && (
                  <code>版本 {project.activeSourceVersionId.slice(0, 8)}</code>
                )}
              </div>
            </div>
            <button
              className="secondary"
              type="button"
              onClick={() => project && openConversation(project)}
              disabled={busy || project.status !== "READY"}
            >
              + 新会话
            </button>
          </section>

          <div className="workspace-grid">
            <section className="chat-panel">
              <div className="panel-heading">
                <div><div className="step-label">02 / 代码问答</div><h2>{conversation?.title ?? "分析会话"}</h2></div>
                <span className="model-badge">VECTOR RAG</span>
              </div>
              <div className="messages" aria-live="polite">
                {(!conversation || conversation.messages.length === 0) && (
                  <div className="empty-chat">
                    <div className="empty-mark">?</div>
                    <h3>从一个具体问题开始</h3>
                    <p>例如：用户登录逻辑在哪里？订单金额如何校验？配置从哪里加载？</p>
                    <div className="suggestions">
                      {["登录逻辑在哪里？", "订单金额如何校验？", "这个项目有哪些核心服务？"].map((item) => (
                        <button key={item} type="button" onClick={() => setQuestion(item)}>{item}</button>
                      ))}
                    </div>
                  </div>
                )}
                {conversation?.messages.map((message) => (
                  <MessageBubble key={message.messageId} message={message} onCitation={showSource} />
                ))}
                {busy && conversation && (
                  <div className="thinking"><span /><span /><span /> 正在检索真实源码并生成回答</div>
                )}
              </div>
              <form className="composer" onSubmit={ask}>
                <textarea
                  aria-label="输入代码问题"
                  placeholder="针对这个项目提问…"
                  value={question}
                  onChange={(event) => setQuestion(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" && !event.shiftKey) {
                      event.preventDefault();
                      event.currentTarget.form?.requestSubmit();
                    }
                  }}
                  disabled={busy || !conversation}
                  rows={2}
                />
                <button type="submit" disabled={busy || !question.trim() || !conversation} aria-label="发送问题">→</button>
              </form>
            </section>

            <aside className="evidence-panel">
              <div className="panel-heading">
                <div><div className="step-label">03 / 源码证据</div><h2>可核查引用</h2></div>
              </div>
              {preview ? (
                <div className="source-view">
                  <div className="source-path">{preview.path}</div>
                  <div className="source-range">L{preview.startLine}–L{preview.endLine}</div>
                  <pre>{preview.content}</pre>
                  <button className="text-button" type="button" onClick={() => setPreview(null)}>← 返回引用列表</button>
                </div>
              ) : (
                <CitationList conversation={conversation} onCitation={showSource} />
              )}
            </aside>
          </div>
        </main>
      )}
      <footer>
        <span>CodeMind AI · 真实源码检索</span>
        <span>React / Spring Boot / FastAPI / Qdrant</span>
      </footer>
    </div>
  );
}

function MessageBubble({ message, onCitation }: {
  message: Message;
  onCitation: (citation: Citation) => void;
}) {
  if (message.role === "USER") {
    return <div className="message user-message"><div className="bubble">{message.content}</div></div>;
  }
  return (
    <div className="message assistant-message">
      <div className="avatar">CM</div>
      <div className="assistant-content">
        <div className="bubble">{message.content}</div>
        {message.citations.length > 0 && (
          <div className="inline-citations">
            {message.citations.map((citation) => (
              <button key={citation.citationIndex} type="button" onClick={() => onCitation(citation)}>
                [{citation.citationIndex}] {citation.filePath}:{citation.startLine}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function CitationList({ conversation, onCitation }: {
  conversation: Conversation | null;
  onCitation: (citation: Citation) => void;
}) {
  const citations = conversation?.messages.flatMap((message) => message.citations) ?? [];
  if (citations.length === 0) {
    return (
      <div className="empty-evidence">
        <div className="evidence-glyph">{`{ }`}</div>
        <p>回答引用的文件、行号和代码片段会显示在这里。</p>
      </div>
    );
  }
  return (
    <div className="citation-list">
      {citations.map((citation) => (
        <button
          className="citation-card"
          key={`${citation.filePath}-${citation.startLine}-${citation.citationIndex}`}
          onClick={() => onCitation(citation)}
          type="button"
        >
          <div className="citation-top">
            <span className="citation-index">[{citation.citationIndex}]</span>
            <span className="score">{citation.score == null ? "证据" : citation.score.toFixed(3)}</span>
          </div>
          <strong>{citation.filePath}</strong>
          <small>第 {citation.startLine}–{citation.endLine} 行</small>
          <pre>{citation.snippet.slice(0, 260)}{citation.snippet.length > 260 ? "…" : ""}</pre>
        </button>
      ))}
    </div>
  );
}
