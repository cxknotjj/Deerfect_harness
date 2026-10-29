# 🔁 断点续跑

> 复杂编排基于自研引擎阶段检查点的断点续跑：断开时机与续跑行为、API 与 CLI 用法。

---

Graph 编排路径（内部枚举 `COMPLEX`）按阶段落检查点到 `orchestration_checkpoint` 表（goal 维度；阶段 `LEAD_DONE` / `SUBTASKS_DONE` / `DONE`）：

| 断开时机 | 续跑行为 |
|---|---|
| ✅ 编排已完整跑完 | 零 LLM 调用，直接回放最终回答 |
| ⏸️ 子任务批已完成、聚合中断 | 只补跑聚合（打字机输出），子任务结果复用 |
| ⏹️ 更早断开（如子任务批执行中） | 已完成阶段不重跑，只补执行缺口 |
| 🚫 无任何检查点 | 快速失败：提示该 goal 未走过复杂路径 |

```bash
# API 方式
curl -N -X POST "http://localhost:8080/api/chat/resume?goalId=<goalId>"

# CLI 方式
/resume <goalId>
```

> [!NOTE]
> `goal` 不存在返回 400；仍在执行中返回 409；无检查点时流内发 `error` 事件。


---

[⬅ 返回 README](../../README.md)
