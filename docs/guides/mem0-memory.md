# 长期记忆插件（mem0）

> 通过插件系统接入 mem0：一轮成功对话后异步写入，下一轮用当前用户消息检索并注入 system prompt。
> 默认关闭；关闭时无外部调用、无注入段。

## 1. 前置条件

- Python 3.10+。
- OpenAI 兼容的 LLM key 与 embedding key（默认 DeepSeek 抽取 + DashScope embedding：`deepseek-chat` + `text-embedding-v3`）。
- 无需 Docker / PostgreSQL：仓库内 `plugin-mem0-local/` 用 mem0ai + 嵌入式 Chroma（文件持久化）。

## 2. 启动本地服务

```powershell
cd plugin-mem0-local
copy .env.example .env    # 填写 MEM0_LLM_API_KEY / MEM0_EMBEDDER_API_KEY
.\start.ps1               # 自动建 venv、装依赖，监听 http://127.0.0.1:8888
```

服务暴露 `POST /memories`、`POST /search`，数据在 `plugin-mem0-local/data/`。

环境变量（不落库、不提交）：

```bash
export MEM0_BASE_URL=http://127.0.0.1:8888
export MEM0_API_KEY=local-no-auth   # 本地服务不鉴权，填非空占位即可
```

`deerfect-server/src/main/resources/application-agent.yaml`：

```yaml
app:
  memory:
    mem0:
      enabled: true          # 总开关；true 时才创建客户端/注入段/写入投递
      base-url: ${MEM0_BASE_URL:http://127.0.0.1:8888}
      api-key: ${MEM0_API_KEY:}
      search-enabled: true   # 只读不写时关 write-enabled
      write-enabled: true
      top-k: 5
      max-chars: 1200
      connect-timeout-ms: 2000
      read-timeout-ms: 3000
    write-queue-size: 200    # 异步写入队列
    dedupe-capacity: 1024    # 同 turnId 去重窗口
```

也可用环境变量覆盖开关：`APP_MEMORY_MEM0_ENABLED=true`。

启动成功后日志会出现：

```text
[plugin] 已加载插件 mem0 (MEMORY, enabled=true)
```

`enabled=true` 但 `base-url` / `api-key` / 数值配置缺失时，启动直接失败（错误信息不含密钥）。

## 3. 验收

### 3.1 写入与召回

1. 会话中发送：`我叫小林，喜欢简洁的回答`
2. 日志出现 `[memory] plugin=mem0 op=remember result=ok`
3. 新开会话问：`我叫什么？`
4. 回答包含「小林」，日志出现 `op=search hits>=1`

### 3.2 关闭即无调用

`enabled: false` 重启后重复一轮对话：日志无 `[memory]`，mem0 侧无新记录。

### 3.3 故障降级

停掉 mem0 容器后发起对话：用户仍收到正常回答；日志出现 `result=error`（超时/连接失败），
单轮额外等待不超过 `read-timeout-ms`。

## 4. 作用域与优先级

| 维度 | 取值 |
| --- | --- |
| `user_id` | `session.creator`（如 `cli` / `qq:123`） |
| `agent_id` | 会话绑定的 Agent 名 |
| `run_id` | 会话 id（仅追溯） |
| 注入次序 | 显式记忆 140 → 长期记忆 145 → 画像 150 |

冲突优先级：**显式记忆 > 长期记忆 > 画像 > 会话摘要**。

已知限制：`cli` / `anonymous` 是共享标识，不同 CLI 用户会共享一份记忆。

## 5. 排障

| 现象 | 排查 |
| --- | --- |
| 启动报配置错误 | `enabled=true` 时 `base-url` / `api-key` 是否为空 |
| 日志 401 | `MEM0_API_KEY` 是否为该实例有效 key |
| 永远无召回 | `search-enabled`、该 `user_id` 是否已有记录、`score` 方向以服务实际返回为准 |
| 写入重复 | 确认只经 `TurnPersistence.writeBackBoth` 投递；检查 `dedupe-capacity` |
| CLI 用户串记忆 | 已知限制，需要区分用户标识 |
| 启动日志提示 Chroma 不支持 keyword search | 正常：Chroma 只做语义检索，BM25 混合检索被关闭 |
| 日志提示 spaCy 未安装 | 可选优化，不影响写入/召回；需要时 `pip install mem0ai[nlp]` |
| 某 provider 免费额度耗尽（403） | 换 `MEM0_LLM_BASE_URL/MODEL/API_KEY`，embedding 仍需保留可用的 DashScope key |

## 6. 与现有记忆的边界

mem0 只新增「跨会话自动提取的事实与实体关系」，不替换会话快照、滚动摘要、用户画像、显式记忆；
数据存在 mem0 自己的 Postgres/pgvector，本项目**不新增数据库迁移**。
