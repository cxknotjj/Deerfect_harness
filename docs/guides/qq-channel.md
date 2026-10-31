# 🤖 QQ 机器人

> README「QQ 机器人」一节的完整版：NapCat 双通道接入步骤、触发方式、指令交互、单条回复与表情包参数。

---


除 CLI / REST 之外的第三个入口：基于 **OneBot 11 协议（HTTP POST 模式）** 经 [NapCat](https://napneko.github.io/) 对接 QQ 群聊与私聊。消息进入后与 REST 全链路一致（会话记忆 → Agentic / Graph 路径分流 → 编排），回复整段单条发出（超长按上限截断）。

| 能力 | 说明 |
|---|---|
| 💬 群聊触发 | `group-trigger.mode`: `at`（@机器人；`reply-to-bot=true` 时回复机器人消息也触发）/ `prefix`（默认 `/ai`）/ `all`；私聊直答，可选 `private-allow-users` 白名单 |
| 📝 单条回复 | 整段单条发送；超过 `reply.max-length`（默认 3000 字）截断并补 `…`；群聊带引用原消息，`reply.at-asker=true` 时首条 @ 提问者 |
| 🧵 指令 | `/new` `/reset`（清空绑定、下条重开新会话）、`/help`、`/history [n]`（默认 5 条，不耗 LLM）、`/agent [名]`（查看/切换 Agent，切换需已有会话）、`/recall`（撤回机器人上一条消息） |
| 😀 表情包 | LLM 在回复末尾输出 `[emoji:分类]`（happy / angry / shock / chill / social / abstract / festive / none）；正文剥离标记后发送，再按分类从 `channel/emoji-index.json` 随机取一张（图片 `base64://` 内联、跨机部署可用）；`probability` / `send-delay-ms` 控频与节奏 |
| 🧠 会话记忆 | 同一 QQ 用户/群自动延续多轮上下文；`/new` / `/reset` 删除绑定行后下条消息建档新会话；harness 删除会话时同步清理绑定 |
| 🚦 防刷 | 同用户限频（`rate-limit.per-user-seconds`）+ 私聊白名单（`private-allow-users`，空 = 不限制）；群聊暂无群号白名单 |
| ⏱️ 聊天超时 | `chat-timeout-seconds` 超时中断并放弃回复（后台任务取消），防 LLM 卡死占满线程池 |
| 🎯 指定 Agent | `agent-id` 仅在会话首次建档时绑定，`/agent <名>` 可随时切换。绑定非 general 的专属 Agent 走 Agentic 直答（跳过 Graph 判定）；general 仍走统一路由（SIMPLE/COMPLEX） |

**接入步骤**（NapCat 侧建议 Docker 部署，只允许 HTTP POST、不用 WebSocket）：

1. NapCat 配置 `onebot11` 网络双通道——正向 API + 反向上报：

   ```jsonc
   {
     "httpServers": [{ "name": "java-harness", "host": "0.0.0.0", "port": 3000,
                        "enableCors": false, "enableWebsocket": false,
                        "messagePostFormat": "array", "token": "<与 napcat.api-token 一致>" }],
     "httpClients": [{ "name": "report-to-harness", "url": "http://<harness主机>:8080/onebot/event",
                        "messagePostFormat": "array", "reportSelfMessage": false,
                        "token": "<与 napcat.event-secret 一致>" }]
   }
   ```

2. harness 侧 `application-qq.yaml` 的 `napcat:` 块按注释配置（`enabled` / `api-base-url` / `api-token` / `self-id` 等，全部有默认值）；不接入设 `napcat.enabled: false` 即可
3. 表情包（可选）：图片放 `channel/emojis/`（不进 git，自行放置），在 `channel/emoji-index.json` 登记表情名 → 图片文件（表情名前缀即分类，如 `happy_01` → `happy`）

> [!NOTE]
> 跨机部署（NapCat 在远端 Docker、harness 在本机）：`api-base-url` 用 `ssh -L 3000:127.0.0.1:3000` 拉回；上报方向 `httpClients` 填 `http://172.17.0.1:8080/onebot/event`，配合 `ssh -R 8080` 反向隧道 + 远端 socat 转发到本机 harness，完整命令见 [dev.md 3.1](../dev.md#31-qq-机器人联调前提远程-napcat)。


---

[⬅ 返回 README](../../README.md)
