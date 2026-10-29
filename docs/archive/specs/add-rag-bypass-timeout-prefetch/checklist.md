# Checklist

- [x] 嵌入接口挂起时，聊天请求在 search-timeout-seconds 内降级回答（不再分钟级阻塞），warn 日志可见超时事件与耗时（对应 TODO 验收原文）——单测以 sleep 挂起 mock 验证 1s 限时降级并断言秒级耗时；真实端点手工端到端验收待运行环境（与知识库增强条目同口径）
- [x] `search-timeout-seconds=0` 时检索行为与治理前逐字节一致（无池包裹直通）——单测覆盖且直通模式异常传播语义保持
- [x] 正常检索的命中渲染、出处透出（recentSources）、预算截留逻辑零变化（KnowledgeRetrieverTest 存量用例全绿）
- [x] 路径 A：入口预取后 general 节点 `buildKnowledgeBlock` 缓存命中，嵌入/PG 不重复调用（verify 次数断言）
- [x] 编排路径：专家节点（query=子任务描述）不命中缓存、按子任务精准现查；lead 节点（query=objective=用户消息）可命中复用（query/kb 双重校验语义保证）
- [x] kb 绑定列表不一致（内容或顺序）时不命中，正常现查
- [x] 预取无命中/异常/超时均不写缓存、不阻断主链路（静默降级）——doPrefetch 与 prefetch 双层 catch
- [x] ChatServiceImpl 同步与流式两入口均完成 judge/预取并行：RAG 在 judge LLM 调用期间执行，总耗时 ≈ max(judge, RAG)——预取 submit 先于 resolveAgent、await 在其后
- [x] RouteJudge 语义零改动：路由结果、异常兜底 SIMPLE/会话 Agent、日志格式与现状一致（resolveAgent/sessionAgentName 未动一行，ChatServiceImplTest 存量 judge 用例全绿）
- [x] `app.knowledge.enabled=false` 时不提交预取、不建线程池，行为与现状一致（newPrefetchPool 返回 null，存量 null-retriever 用例全绿）
- [x] 数值零硬编码：超时值唯一来源 application.yaml `app.knowledge.search-timeout-seconds`（默认 10，0=关闭）；PREFETCH_GRACE_SECONDS=2 为汇合窗口代码常量（非预算值，预取内部已被 search-timeout 限时）
- [x] HARNESS_TODO L71-73 已勾选并标注完成说明；README 检索参数说明已补
- [x] 全量回归 `mvn -s .mvn/settings.xml test` 全绿（568 例，0 失败 0 错误 1 跳过，BUILD SUCCESS）
- [x] 未执行任何 git commit/push（等用户明示）
