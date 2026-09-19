# 🤖 QQ 机器人

> README「QQ 机器人」一节的完整版：NapCat 双通道接入步骤、渐进发送、表情包、拟人延迟与全部参数。

---


除 CLI / REST 之外的第三个入口：基于 **OneBot 11 协议（HTTP POST 模式）** 经 [NapCat](https://napneko.github.io/) 对接 QQ 群聊与私聊。消息进入后与 REST 全链路一致（会话记忆 → SIMPLE/COMPLEX 分流 → 编排），回复按真人节奏分段发出。

| 能力 | 说明 |
|---|---|
| 💬 群聊 @ 唤醒 | `group-trigger.mode`: `at`（@机器人触发）/ `prefix`（命令前缀）/ `all`；私聊直答 |
| 📝 渐进发送 | 回复按 ≤`split-chars` 字切片（段落 → 换行 → 。！？句读 → 整块），像真人连发；超 `max-chunks` 条合并为最后一条 |
| ⏱️ 动态拟人延迟 | 条间停顿 = 基础延迟 + 字数 × `delay-factor`（0.1s/字）± 随机扰动，`max-delay-ms` 截断；首条秒回 |
| 😀 表情包 | 回复每条切片后按情绪关键词/句尾符号（~！!）命中自动甩表情（映射表 `channel/emoji-index.json`，图片 `base64://` 内联、跨机部署可用），`probability` / `max-per-reply` 控频 |
| 🧵 会话记忆 | 同一 QQ 用户自动延续多轮上下文；`@机器人 /reset`（群聊）或 `/reset`（私聊）重置会话 |
| 🚦 防刷 | 同用户限频（`rate-limit.per-user-seconds`）+ 私聊白名单（`private-allow-users`，空 = 不限制） |
| ␥ 聊天超时 | `chat-timeout-seconds` 超时放弃回复（后台跑完仍落会话记忆），防 LLM 卡死占满线程池 |
| 🎯 指定 Agent | `agent-id` 填 agent 表主键后会话绑定该 Agent（knowledge 绑定自动生效），仍走统一路由判定：简单问题直答、复杂问题编排，编排失败降级回该 Agent 重答 |

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

2. harness 侧 `application.yaml` 的 `napcat:` 块按注释配置（`enabled` / `api-base-url` / `api-token` / `self-id` 等，全部有默认值）；不接入设 `napcat.enabled: false` 即可
3. 表情包（可选）：图片放 `channel/emojis/`（不进 git，自行放置），在 `channel/emoji-index.json` 登记表情名 → 文件与情绪 tags

> [!NOTE]
> 跨机部署（NapCat 在远端 Docker、harness 在本机）时 NapCat 侧 `api-base-url` 用 `ssh -L 3000:127.0.0.1:3000` 隧道转发；上报方向反向走 `httpClients` 直推 harness 的 8080。


---

[⬅ 返回 README](../README.md)
