export type ProjectStatus = "INDEXING" | "READY" | "INDEX_FAILED" | "DELETING";

export interface Project {
  projectId: string;
  name: string;
  status: ProjectStatus;
  activeSourceVersionId: string | null;
  indexJobId: string | null;
  errorCode: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PrivacyNotice {
  noticeVersion: string;
  embedding: { baseUrl: string; purpose: string; dataTypes: string[] };
  chat: { baseUrl: string; purpose: string; dataTypes: string[] };
  thirdPartyRetentionNotice: string;
  secretDetectionLimitation: string;
}

export interface Citation {
  citationIndex: number;
  filePath: string;
  startLine: number;
  endLine: number;
  snippet: string;
  score: number | null;
}

export interface Message {
  messageId: string;
  role: "USER" | "ASSISTANT";
  content: string;
  status: "COMPLETED" | "FAILED";
  sourceVersionId: string | null;
  errorCode: string | null;
  citations: Citation[];
  createdAt: string;
}

export interface Conversation {
  conversationId: string;
  projectId: string;
  title: string | null;
  messages: Message[];
  createdAt: string;
  updatedAt: string;
}

export interface AskResponse {
  messageId: string;
  answer: string;
  status: string;
  sourceVersionId: string;
  citations: Citation[];
}

export interface SourceExcerpt {
  sourceVersionId: string;
  path: string;
  startLine: number;
  endLine: number;
  content: string;
}

interface ApiErrorBody {
  errorCode?: string;
  message?: string;
}

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, options);
  if (!response.ok) {
    let body: ApiErrorBody = {};
    try {
      body = (await response.json()) as ApiErrorBody;
    } catch {
      body = {};
    }
    throw new Error(body.message ?? `请求失败（HTTP ${response.status}）`);
  }
  return (await response.json()) as T;
}

export function getPrivacyNotice(): Promise<PrivacyNotice> {
  return request("/api/v1/privacy-notice");
}

export function listProjects(): Promise<Project[]> {
  return request("/api/v1/projects");
}

export function getProject(projectId: string): Promise<Project> {
  return request(`/api/v1/projects/${projectId}`);
}

export function uploadProject(
  file: File,
  noticeVersion: string,
  externalTransferAccepted: boolean,
): Promise<{ projectId: string; sourceVersionId: string; indexJobId: string }> {
  const body = new FormData();
  body.append("file", file);
  body.append("noticeVersion", noticeVersion);
  body.append("externalTransferAccepted", String(externalTransferAccepted));
  return request("/api/v1/projects/upload", { method: "POST", body });
}

export function createConversation(projectId: string): Promise<{ conversationId: string }> {
  return request("/api/v1/conversations", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ projectId, title: "代码分析会话" }),
  });
}

export function getConversation(conversationId: string): Promise<Conversation> {
  return request(`/api/v1/conversations/${conversationId}`);
}

export function askQuestion(conversationId: string, question: string): Promise<AskResponse> {
  return request(`/api/v1/conversations/${conversationId}/questions`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ question }),
  });
}

export function readSource(
  versionId: string,
  path: string,
  startLine: number,
  endLine: number,
): Promise<SourceExcerpt> {
  const params = new URLSearchParams({
    path,
    startLine: String(startLine),
    endLine: String(endLine),
  });
  return request(`/api/v1/source-versions/${versionId}/file?${params.toString()}`);
}
