import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App";

const privacy = {
  noticeVersion: "2026-10-29.v1",
  embedding: {
    baseUrl: "https://embedding.example/v1",
    purpose: "生成向量",
    dataTypes: ["JAVA_OR_PYTHON_CODE_CHUNK"],
  },
  chat: {
    baseUrl: "https://chat.example/v1",
    purpose: "生成回答",
    dataTypes: ["USER_QUESTION", "RETRIEVED_CODE_CHUNK"],
  },
  thirdPartyRetentionNotice: "请自行确认第三方保留政策。",
  secretDetectionLimitation: "本地检测无法保证识别所有秘密。",
};

describe("CodeMind 前端", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      const body = url.includes("privacy-notice") ? privacy : [];
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("展示产品定位和上传入口", async () => {
    render(<App />);

    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("让 AI 读懂你的整个代码项目");
    expect(screen.getByText("选择 ZIP 文件")).toBeInTheDocument();
    expect(await screen.findByText(/embedding.example/)).toBeInTheDocument();
  });

  it("未选择文件并确认隐私时不能上传", async () => {
    render(<App />);

    const button = screen.getByRole("button", { name: /上传并建立索引/ });
    expect(button).toBeDisabled();
    expect(await screen.findByText("源码外发告知")).toBeInTheDocument();
  });
});
