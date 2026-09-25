# 🏗️ 架构总览

> README 精简后迁入：整体架构图与相关文档入口。

---

```mermaid
flowchart TD
    A[🖥️ CLI / REST 请求] --> B[ChatController<br/>Harness 外壳入口]
    B --> C{🧭 RouteJudge<br/>LLM 判定 SIMPLE / COMPLEX}
    C -->|SIMPLE 简单问题| D[⚡ GeneralAssistantAgent<br/>单次调用 · 逐 token 流式]
    C -->|COMPLEX 复杂任务| E[🕸️ MultiAgentGraphAgent<br/>StateGraph 编排]
    E --> F[🧩 Lead 拆解<br/>至多 4 条 · 禁凑数]
    F --> G1[🔍 researcher]
    F --> G2[💻 coder]
    F --> G3[📊 analyst]
    F --> G4[✍️ writer]
    G1 & G2 & G3 & G4 --> H[📌 Aggregator 聚合<br/>打字机输出最终回答]
    D --> I[(🗄️ Goal 状态 + 会话记忆<br/>+ LLM 调用观测落库)]
    H --> I
    I --> J[📤 统一出口<br/>同步 JSON / SSE 流式]
```

> [!TIP]
> 数据流细节见 [`docs/data-flow.md`](data-flow.md)，落地 TODO 见 [`docs/HARNESS_TODO.md`](HARNESS_TODO.md)，测试全景见 [`docs/guides/functional-testing.md`](functional-testing.md)。


---

[⬅ 返回 README](../README.md)
