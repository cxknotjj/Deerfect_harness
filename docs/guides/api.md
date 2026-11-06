# 🌐 REST 接口

> 全部 REST 端点、知识库管理页与 RAG 问答说明。

---

**聊天**

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/chat` | 💬 同步聊天：`{"message":"你好","agentId":1}` |
| `POST` | `/api/chat/stream` | 📺 流式聊天（SSE）：同请求体，逐 token 推送 |
| `POST` | `/api/chat/resume?goalId=` | 🔁 复杂编排断点续跑，响应格式与 `/stream` 一致 |
| `GET` | `/api/chat/goal-status?goalId=` | 🔎 查询单个 goal 的执行状态 |

**会话与 Agent**

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/harness/agents` | 🧩 已注册的 Agent 列表 |
| `GET` | `/api/harness/sessions?page=&size=` | 📋 会话分页列表（默认 page=1 / size=10） |
| `POST` | `/api/harness/sessions?name=` | 🆕 新建会话（可选 `name`），返回 sessionId/name |
| `DELETE` | `/api/harness/sessions/{sessionId}` | 🗑 删除会话（软删 + 快照清理，调用日志与 goal 保留） |
| `POST` | `/api/harness/sessions/{sessionId}/agent?agentId=` | 🎭 切换会话绑定的 Agent |
| `GET` | `/api/harness/sessions/{sessionId}/messages` | 📜 会话历史消息（无历史返回空列表） |

**目标与定时任务**

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/harness/submit?agent=&objective=` | 📤 提交一个异步目标（「现在跑一次」） |
| `GET` | `/api/harness/goals` | 🎯 目标（含聊天记录）与状态 |
| `GET` | `/api/harness/goals/{id}` | 🎯 查询单个目标状态 |
| `GET` | `/api/harness/goals/active?sessionId=` | 📌 会话当前活跃的长期目标（无目标返回空列表） |
| `POST` | `/api/harness/goals/active` | 📌 登记长期目标（body：`sessionId` + `objective`，每轮注入 system） |
| `POST` | `/api/harness/goals/{id}/complete` | ✅ 标记长期目标完成（只接受 ACTIVE，重复完成 400） |
| `GET` | `/api/schedules` | ⏰ 定时任务列表 |
| `POST` | `/api/schedules` | ⏰ 新建定时任务（body：`objective` / `agentName` / `cron` / `notifyChannel`；cron 为 Spring 六段表达式，间隔须 ≥30 秒） |
| `DELETE` | `/api/schedules/{id}` | ⏰ 删除定时任务（已触发的 goal 不受影响） |

**观测与监控**

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/llm-calls?sessionId=&limit=` | 🧮 LLM 调用观测：耗时 / token / 成败（默认 50 条） |
| `GET` | `/api/tool-calls?sessionId=&limit=` | 🔧 工具调用观测 |
| `GET` | `/api/kb-retrievals?sessionId=&limit=` | 📚 RAG 检索观测 |
| `GET` | `/api/monitor/context?sessionId=` | 📊 最近一次请求的上下文构成快照（分段占比；无快照 404） |
| `GET` | `/api/monitor` | 📈 运行时指标快照（与 `/actuator/prometheus` 同源） |

**配置、鉴权与渠道**

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` / `POST` | `/api/providers` | 🔌 模型-供应商映射：列表 / 新增（新增后热刷新） |
| `GET` | `/api/skills` | 🧰 已注册技能列表 |
| `POST` | `/api/auth/login` | 🔐 口令登录（换 HttpOnly Cookie） |
| `GET` | `/api/auth/me` | 🔐 当前登录态 |
| `POST` | `/api/auth/logout` | 🔐 登出 |
| `POST` | `/onebot/event` | 🤖 QQ 渠道 OneBot 11 上报端点（`channel-qq` 模块，NapCat 调用） |

**知识库**

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/knowledge/sync` | 📚 知识库增量摄取：扫描 knowledge/ 目录，mtime 变更文档重嵌入，并清理磁盘已删文档的孤儿向量 |
| `GET` | `/api/knowledge/documents?page=&size=` | 📚 知识库摄取台账分页 |
| `GET` | `/api/knowledge/search?q=&kb=&full=` | 📚 调试检索：检索命中片段与相关度（不注入 prompt）；`full=true` 额外返回片段原文 |
| `POST` | `/api/knowledge/upload` | 📚 multipart 上传 .md/.txt（≤1MB，可选 `kb` 表单字段归库），只落盘不摄取，重名覆盖 |
| `DELETE` | `/api/knowledge/documents/{name}` | 📚 删除指定知识文档（向量 chunk + 台账） |

> 定时任务与长期目标的差别：`/api/schedules` 是「到点自己开始」（一次触发产生一个 goal），
> `/goals/active` 是「记住要长期推进」（每轮对话注入 system，手动标记完成）。

> [!NOTE]
> `agentId` 可选（对应 agent 表主键）：为空走默认 Agent（general）。
> 知识库端点需 `app.knowledge.enabled=true`（默认 true）且配置好 pgvector/嵌入端点；未启用时返回 503。
> 知识库另有单文件 Web 管理页：浏览器打开 **`http://localhost:8080/knowledge.html`**（台账/删除/同步/上传/调试检索，未启用时展示降级指引）。

### 📚 知识库问答（RAG）

把文档放进 `knowledge/` 目录（`.md` / `.txt`，支持 front-matter `title:`），摄取后路径 A/B 回答自动检索注入，带【出处N】内联引用与来源尾注。触发是组装 prompt 前的旁路检查，五层条件任一不满足即静默降级、主链路零感知。

> [!TIP]
> 多知识库与 agent 绑定、目录监听 / BM25 混合检索等增强配置见 **[`docs/guides/knowledge-rag.md`](knowledge-rag.md)**；决策流程与时序图见 [`docs/data-flow.md` 5i 节](../data-flow.md#5i-rag-知识检索注入数据流prompt-组装前旁路)。


---

[⬅ 返回 README](../../README.md)
