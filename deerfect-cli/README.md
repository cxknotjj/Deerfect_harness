# cli

终端聊天客户端：**独立进程**，只通过 HTTP 跟主服务说话（`POST /api/chat/stream`，SSE 流式），
不直接依赖 `server`，也不碰数据库。

```text
终端  ──HTTP/SSE──▶  server:8080  ──▶  agent
```

## 目录结构

```text
deerfect-cli/
├── pom.xml                       # 可执行 jar，mainClass = ChatCli
└── src/
    ├── main/java/com/dark/javaHarness/cli/
    │   ├── (root)   (5)          # 启动、会话状态、命令分发、续跑、流渲染
    │   ├── api/     (1)          # HTTP + SSE 客户端
    │   ├── input/   (1)          # 终端输入（行编辑与历史）
    │   └── render/  (4)          # ANSI 着色、Markdown 渲染、spinner、终端输出
    └── test/java/com/dark/javaHarness/cli/
```

### 根包

| 类 | 作用 |
| --- | --- |
| `ChatCli` | 入口。只负责启动与依赖组装：输入输出通道、会话装载、续跑恢复、命令循环 |
| `CommandDispatcher` | 斜杠命令：`/help` `/new` `/agent` `/routing` `/resume` `/provider` `/schedule` `/goal` `/remember` `/memories` `/forget` |
| `StreamRenderer` | 回合执行与逐帧渲染：解析 SSE 进度帧、token 流、回合小结与会话 ID 记忆 |
| `ChatSessionState` | 可变选择状态：当前会话 / Agent / 执行路径 / 续跑目标 |
| `ResumeStateStore` | 续跑状态持久化（退出后可 `/resume` 接着跑） |

### `api/`

`ChatApiClient`：把 CLI 的请求翻译成 HTTP，并把 SSE 帧解析成事件回调。所有网络细节都在这里，
上层不感知 HTTP。

### `input/`

`TerminalInput`：行编辑、方向键、历史命令。Java 没有现成的 readline，这部分是手写的。

`open()` 会**先判断再探测**：`System.console() == null` 且 `TERM` 未设或为 `dumb` 时直接
降级为行式读取。不这么做的代价很实在——JLine 判断「系统流归谁管」会跑外部 `test -t 0`
且 `waitFor` 无超时，在 Surefire fork / CI 里能卡 40 秒（详见 `docs/dev.md`）。
`isInteractive(hasConsole, term)` 是纯函数，取舍逻辑有单测。

### `render/`

| 类 | 作用 |
| --- | --- |
| `Ansi` | ANSI 转义序列常量与降级判断（不支持的终端自动关闭着色） |
| `MarkdownAnsiRenderer` | Markdown → 终端样式（标题/代码块/列表/强调） |
| `Spinner` | 等待阶段的转圈动画 |
| `TerminalRenderer` | 阶段行、工具调用行与最终输出的落盘式渲染 |

## 边界

- **不实现业务**：路由判断、会话管理、工具调用全部在 server，CLI 只是「输入 + 渲染」。
- **不发消息到 IM**：渠道是 server 的事，CLI 只跟自己的终端说话。
- **不做鉴权存储**：用 `server.security` 的 token 时通过环境变量或参数传入。
- 覆盖率门槛很低（当前 23%）：交互渲染难以自动化，核心逻辑（命令解析、续跑状态）
  另有单测，UI 部分靠手工验证。
