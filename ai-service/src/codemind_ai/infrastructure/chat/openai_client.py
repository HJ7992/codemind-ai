from collections.abc import Sequence

import httpx

from codemind_ai.infrastructure.chat.base import ChatError
from codemind_ai.infrastructure.qdrant.base import StoredChunk

_SYSTEM_PROMPT = """你是代码分析助手。只能依据给出的代码证据回答，使用 [1]、[2] 这样的编号引用。
不得编造文件路径、行号或未出现的代码。证据不足时明确说明无法回答。回答使用用户提问的语言。"""


class OpenAIChatClient:
    def __init__(
        self,
        base_url: str,
        model: str,
        api_key: str,
        timeout_seconds: float = 45.0,
        client: httpx.Client | None = None,
    ) -> None:
        self.endpoint = f"{base_url.rstrip('/')}/chat/completions"
        self.model = model
        self.api_key = api_key
        self.timeout_seconds = timeout_seconds
        self.client = client

    def answer(self, question: str, evidence: Sequence[StoredChunk]) -> str:
        if not evidence:
            raise ValueError("无证据时不得调用 Chat 模型")
        blocks = [
            f"[{index}] {chunk.file_path}:{chunk.start_line}-{chunk.end_line}\n{chunk.snippet}"
            for index, chunk in enumerate(evidence, start=1)
        ]
        user_content = f"问题：{question}\n\n代码证据：\n" + "\n\n".join(blocks)
        try:
            if self.client is not None:
                response = self.client.post(
                    self.endpoint,
                    headers={"Authorization": f"Bearer {self.api_key}"},
                    json={
                        "model": self.model,
                        "messages": [
                            {"role": "system", "content": _SYSTEM_PROMPT},
                            {"role": "user", "content": user_content},
                        ],
                        "temperature": 0,
                    },
                    timeout=self.timeout_seconds,
                )
            else:
                with httpx.Client(timeout=self.timeout_seconds) as client:
                    response = client.post(
                        self.endpoint,
                        headers={"Authorization": f"Bearer {self.api_key}"},
                        json={
                            "model": self.model,
                            "messages": [
                                {"role": "system", "content": _SYSTEM_PROMPT},
                                {"role": "user", "content": user_content},
                            ],
                            "temperature": 0,
                        },
                    )
        except httpx.HTTPError as exception:
            raise ChatError("回答生成服务暂时不可用") from exception
        if response.status_code != 200:
            raise ChatError("回答生成服务返回错误")
        try:
            content = response.json()["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError, ValueError) as exception:
            raise ChatError("回答生成服务响应格式无效") from exception
        if not isinstance(content, str) or not content.strip():
            raise ChatError("回答生成服务返回空内容")
        return content.strip()
