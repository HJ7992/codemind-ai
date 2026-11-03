"""本地演示用 OpenAI 兼容 Mock，不用于生产，也不会调用外部模型。"""

import hashlib
import json
import math
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DIMENSIONS = 64


def _tokens(text: str) -> list[str]:
    words = re.findall(r"[A-Za-z_][A-Za-z0-9_]*|[\u4e00-\u9fff]{1,2}", text)
    return [word.lower() for word in words]


def _embedding(text: str) -> list[float]:
    vector = [0.0] * DIMENSIONS
    for token in _tokens(text):
        digest = hashlib.sha256(token.encode()).digest()
        index = int.from_bytes(digest[:4], "big") % DIMENSIONS
        vector[index] += 1.0 if digest[4] % 2 == 0 else -1.0
    norm = math.sqrt(sum(value * value for value in vector)) or 1.0
    return [value / norm for value in vector]


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        self._send(200, {"status": "ok", "mode": "local-mock"})

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length) or b"{}")
        if self.path.endswith("/embeddings"):
            inputs = body.get("input", [])
            data = [
                {"index": index, "embedding": _embedding(text)}
                for index, text in enumerate(inputs)
            ]
            self._send(200, {"data": data, "model": body.get("model", "mock")})
            return
        if self.path.endswith("/chat/completions"):
            self._send(
                200,
                {
                    "choices": [
                        {
                            "message": {
                                "content": (
                                    "根据检索到的代码证据，相关实现见 [1]。"
                                    "请结合引用中的真实文件路径、行号和代码片段核对。"
                                )
                            }
                        }
                    ]
                },
            )
            return
        self._send(404, {"error": "not found"})

    def _send(self, status: int, payload: dict[str, object]) -> None:
        encoded = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, format: str, *args: object) -> None:
        return


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 8010), Handler).serve_forever()
