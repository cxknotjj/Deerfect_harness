# plans 归档

已实施的历史计划，正文冻结、不再更新；结论与替代方案见各文件头部。

| 计划 | 结论 |
| --- | --- |
| `2026-08-25-chat-reactive-streaming-plan.md` | SSE 走 MVC + `Flux<String>`，保留 `/api/chat` 同步端点 |
| `2026-11-19-agent-model-binding-settings-plan.md` | 原计划两个同级页，实际合并为设置页单页「路由与 Agent」 |
| `2026-11-19-context-window-slider-plan.md` | 通过 `PUT /api/admin/models/{id}/context-window` 单键更新 |
| `napcat-deerfect-harness-integration-plan.md` | HTTP POST 上报 + HTTP API 下行，未用 WebSocket |
| `trae-prompt-dynamic-loading.md` | IDE 侧开发计划：skill / tool / MCP 动态装配（历史记录） |
| `trae-split-long-classes.md` | IDE 侧开发计划：超长类拆分（历史记录） |

> 新计划不再进这里：在途变更写 `specs/NNN-slug/plan.md`，完成后归档到 `docs/archive/specs/`。
