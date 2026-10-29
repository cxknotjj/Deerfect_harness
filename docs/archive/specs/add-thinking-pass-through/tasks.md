# Tasks

- [x] Task 1: AgentChatPipeline 思考提取通道
  - [x] SubTask 1.1: `tokenStream`/`streamCore`/`streamAttempt` 增加可选 `reasoningTap`（Consumer<String>，null 直通）：在 ChatResponse doOnNext 旁路读 `output.getMetadata().get("reasoningContent")`，非空白即回调；提取 try-catch 静默；tap 只读，不触碰 collected/watchdog/账本
  - [x] SubTask 1.2: `AgentChatCaller` stream/streamWithAssembly 重载链透传 reasoningTap（既有重载委托 null，调用点零改动）
  - [x] SubTask 1.3: 单测——有 reasoning 时 tap 收到增量且返回内容不含思考文本；无 reasoning 时 tap 零回调；metadata 异常静默；既有重载行为不变
- [x] Task 2: agent 表 thinking 列（V33 + 配置读取链）
  - [x] SubTask 2.1: 新建 `V33__agent_thinking_column.sql`：agent 表加 `thinking` TINYINT NULL 列，列注释写明「仅控制是否显示思考内容，并不是控制模型是否思考（模型思考由 model_provider.disable_thinking 决定）」；UPDATE general、researcher 行置 1
  - [x] SubTask 2.2: AgentConfig record 增加 thinking 字段（Boolean，NULL 安全），AgentConfigProvider 读取链带出；单测——列值 1/0/NULL 三态读取
- [x] Task 3: 路径 A 接线（主回答 + 专属 Agent 直答，依赖 Task 1/2）
  - [x] SubTask 3.1: `GeneralAssistantAgent.executeStreamReactive` 流构建时查调用方 agent thinking 配置（一次查询闭包持有），=1 时传入 tap，增量编码为 `ProgressLine.encode("思考", delta)` 合入 SSE 输出流
  - [x] SubTask 3.2: 回归确认——thinking=0/NULL 无思考行；思考行不计入会话内容收集（补一用例）；QQ 阻塞路径不接线（call 链路无 tap）
- [x] Task 4: 复杂路径聚合接线（依赖 Task 1/2）
  - [x] SubTask 4.1: 聚合节点按 aggregator 行 thinking 配置传入 tap，思考增量编码为 ProgressLine 经 `liveTokens` 旁路 sink 发射；确认 `contentSent` 不被进度行置位、END 帧兜底不受影响
  - [x] SubTask 4.2: 单测——聚合思考行出现在合并流中、final 内容与 contentSent 判定不变、aggregator thinking=0 时无思考行
- [x] Task 5: 复杂路径子任务接线（身份 + 节流，依赖 Task 1/2）
  - [x] SubTask 5.1: 新增包私有合帧节流器（零 Spring 装配）：积攒约 300ms 或阈值字符 flush、流正常结束兜底 flush、异常中止丢弃缓冲；参数内置常量不进 yaml
  - [x] SubTask 5.2: 子任务节点按 expert 行 thinking 配置传入 tap：思考增量编码 stage=「思考N」（N=idx+1，1 基）经 `toolEvents` 同款旁路 sink（`tryEmitSerialized` 串行锁）发射
  - [x] SubTask 5.3: 单测——并行子任务思考行带各自序号不串位；节流生效（数千 delta → 行数受控）；终帧 flush；异常中止缓冲丢弃且不影响超时/断连处理；expert thinking=0 时无思考行
- [x] Task 6: 前端「思考」折叠渲染
  - [x] SubTask 6.1: `useChat.ts` 识别 stage 以「思考」开头的行并聚合增量；`TraceView.vue` 渲染灰色折叠区（「思考N」按序号分组对齐子任务；默认折叠可展开），不进回答气泡
  - [x] SubTask 6.2: `npm run build` 通过
- [x] Task 7: 全量验证
  - [x] SubTask 7.1: `mvn -pl server -am test` 0 失败 0 错误
  - [x] SubTask 7.2: 手动 e2e（可选）：配 qwen3.8-max 的会话提问，轨迹区出现思考折叠行（含子任务分组）、正文正常

# Task Dependencies

- Task 1（管道通道）与 Task 2（thinking 列）相互独立，可并行
- Task 3 / Task 4 / Task 5 依赖 Task 1 + Task 2
- Task 6 依赖 Task 3/4/5 的行协议定型（stage 命名）
- Task 7 依赖全部前置任务
