# 🎮 CLI 使用

> CLI 纯 HTTP 客户端的使用方式与全部命令。

---

CLI 是纯 HTTP 客户端（**不监听任何端口**），通过 REST 调用主服务：

```text
你> 你是谁
千问> 我是通义千问，一个AI助手...
```

| 命令 | 作用 |
|---|---|
| 直接输入文本 | 与当前 Agent（默认 general）聊天，多轮记忆自动延续 |
| `/new [名称]` | 🆕 新建会话并切换（旧会话保留） |
| `/agent <id>` / `/agent off` / `/agent` | 🎭 切换到指定 Agent（agent 表主键，同步会话绑定）/ 恢复智能分流 / 查看当前；绑定非 general 的专属 Agent 走 Agentic 直答 |
| `/routing [auto\|agentic\|graph]` | 🧭 查看或固定执行路径，不传参查看当前；`auto` 恢复服务端自动判定 |
| `/resume [goalId]` | 🔁 复杂编排断点续跑：不传参恢复当前会话最近一次编排任务，传参续跑指定 goal（从检查点继续，已完成节点不重跑） |
| `/provider [add <provider> <apiUrl> <模型1,模型2,...>]` | 🔌 查看模型-服务商映射 / 新增供应商（服务端热刷新） |
| `/schedule [list\|add "<cron>" <agent> <目标>\|del <id>]` | ⏰ 定时任务增删查，不传子命令等同 `list`；cron 为 Spring 六段表达式（秒 分 时 日 月 周），间隔须 ≥30 秒 |
| `/goal [查看\|<目标描述> 登记\|done <id> 完成]` | 📌 长期目标：登记后每轮注入 system；`done` 手动收口（不做 LLM 自评完成） |
| `/help` / `/exit` / `/quit` | ❓ 帮助 / 🚪 退出 |

> `/goal` 与 `/schedule` 是两套独立机制：前者是「记得住你在追什么」（常驻上下文），
> 后者是「到点自己开始」（到点自动提交一个 goal，结果可经 QQ 主动推送）。


---

[⬅ 返回 README](../../README.md)
