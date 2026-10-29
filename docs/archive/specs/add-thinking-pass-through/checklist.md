# Checklist

- [x] 管道层提取：reasoningTap 为可选参数，null 直通=既有调用点行为逐字节不变
- [x] 思考增量来自 `AssistantMessage` metadata 键 `reasoningContent`（非空白才回调，异常静默）
- [x] agent 表新增 `thinking` 列：注释写明「仅控制是否显示思考内容，并不是控制模型是否思考」；V33 迁移加列且 general、researcher 置 1
- [x] 接线判定按调用方 agent 的 thinking 配置一次查询闭包持有；thinking=0/NULL 时无思考行（即使模型实际在思考）
- [x] 思考行以 ProgressLine 进度行形态传输（stage 携带归属：「思考 · agent名」/「思考 · 聚合」/「思考N · 专家名」），content flux 与返回内容不含思考文本
- [x] 思考行不落入 `session_messages`（内容收集继续排除进度行），不进入聚合输入
- [x] 路径 A：executeStreamReactive 接线，主回答/专属 Agent 直答（thinking=1）可见思考折叠行
- [x] 复杂路径：聚合思考按 aggregator 配置经 liveTokens sink 以进度行发射，contentSent/END 兜底不受影响
- [x] 子任务思考：expert thinking=1 时 stage=「思考N」（1 基序号）经 toolEvents 同款 sink 发射，并行行互可区分不串位
- [x] 合帧节流生效：思考行数量与 delta 帧数解耦，流结束兜底 flush，异常中止丢弃缓冲且不影响超时/断连处理
- [x] QQ 渠道路径无 tap（阻塞 call 不接线），回复行为不变
- [x] 无 app.* 全局开关：显示范围仅由 agent 表 thinking 列逐 agent 声明，application.yaml 无新增键
- [x] 前端「思考」折叠区：默认折叠可展开、灰色弱化、标题标注归属 agent、不进回答气泡，`npm run build` 通过
- [x] `mvn -pl server -am test` 0 失败 0 错误
- [x] 不改动：model_provider 表与 ThinkingSwitchChatModel（端点口径原样）、LlmCallRecorder/llm_call_log、预算与 watchdog 链路
