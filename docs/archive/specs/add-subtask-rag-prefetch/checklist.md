# Checklist

- [x] ] 已绑定知识库的专家子任务：执行期 `buildKnowledgeBlock` 缓存命中（不现查），回答照常注入【出处N】知识段（verify search 调用次数断言）
- [x] ] 未绑定专家 / 未指派回退专家：预取解析 kb=null 不入池，执行期跳过注入，行为与现状一致
- [x] ] 预取失败（池饱和/检索超时/检索异常/ragPrefetcher=null）四种情况均静默降级，编排主流程零感知，SSE 进度行与现状完全一致
- [x] ] 断点续跑路径（lead 不重跑）subtask 节点钩子同样生效
- [x] ] 同会话入口预取条目与多个子任务预取条目共存互不覆盖；lead 节点（query=objective）在子任务预取后仍缓存命中
- [x] ] 缓存有界：超限整体清空后正常现查；未命中/异常不写缓存（存量语义保持）
- [x] ] 入口预取外部行为零变化：提交时机、2s 汇合窗口、`app.knowledge.enabled=false` 零行为、judge 异常兜底（ChatServiceImplTest 存量用例全绿）
- [x] ] RagPrefetcher bean 化后消费方全部 null 安全（knowledgeRetriever 缺位时 ChatServiceImpl 与编排链路均为零行为）
- [x] ] 子任务预取参数与执行同源：agentName 与 subtask 节点实际 forAgent 一致（含未指派回退专家）、query=子任务原文、sessionId=编排会话（verify 参数断言）
- [x] ] 未布置空槽位不提交预取
- [x] ] V29 绑定迁移幂等：重复执行不报错不重复；coder=java、researcher=java,frontend；analyst/writer/general/wms 等未受影响（对照组成立：编排中出现已绑定与未绑定专家时各自行为符合预期）
- [x] e2e 手工验收（2026-09-29，DEBUG 日志实测 3 轮 COMPLEX 编排）：sync 摄取 java/frontend 后——① lead 出口预取先于子任务执行（日志时序 15:05:00.94 拆解 → 15:05:01.4 预取完成 → 15:05:05 子任务执行）；② 排队子任务执行期「预取命中，跳过检索」（未排队者与预取竞速退化为现查，无回归）；③ 未绑定专家（writer/analyst）全程零检索（对照组生效）；④ 重复提交幂等（同复合键 34ms 间隔命中）；⑤ meta.sources 透出两个绑定库命中（java 1.0 分 + frontend 0.49 分，依赖 meta 求值时序修复，见 Task 5 补充）
- [ ] （遗留，非本 spec 范围）聚合节点改写会丢弃子任务结果中的【出处N】标记——知识块注入与子任务级检索已验证（命中日志 + researcher ctxChars=1924 含知识段），最终回答的引用保留属聚合提示词既有行为，如需保留待后续单独处理
- [x] ] 无新增配置键/硬编码数值；无 TODO 残留
- [x] ] docs/data-flow.md 已补编排预取时序说明；V12 列注释已修正为「NULL/空白=未绑定不检索」
- [x] ] 全量回归 `mvn -B -pl server -am test` 0 失败 0 错误，BUILD SUCCESS
- [ ] 未执行任何 git commit/push（等用户明示）
