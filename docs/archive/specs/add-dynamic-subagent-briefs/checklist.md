# Checklist

- [x] LeadOutputParser.Subtask 含 brief 字段，新格式 `{"desc","agent","brief"}` 三字段正确解析
- [x] 旧格式（对象无 brief / 纯字符串数组）解析为 brief=null，行为与现状一致
- [x] EXPERT_WHITELIST 包含 wms，lead 指派 wms 不再回退 general（子任务由 wms 表行执行、携带 wms_* 工具）
- [x] 白名单外的模板名仍回退 null（执行走 general），现状语义不变
- [x] 状态键 `subtaskBrief_0..3` 已注册 replace 策略，lead 出口逐槽位写入 brief
- [x] 旧 checkpoint（无 brief 键）续跑时 orElse(null) 安全回退，不报错（代码核验：subtask 节点 orElse(null)，测试覆盖读路径）
- [x] executeSubtask 的 user 文本 = brief 非空 ? brief : desc；system/fallbackSystem/工具/预算/记忆策略零改动
- [x] `app.context.subtask-brief-max-chars` 配置生效：yaml 默认 2000、超限在 lead 出口写入前截断 + warn 单行、0=不限制
- [x] V31 迁移 REPLACE 幂等，lead prompt 含 brief 字段说明与任务书四要素要求
- [x] RAG 预取参数不变：query=desc、agentName=resolvedExpert，lead 出口主提交与 subtask 入口补提交均未改动
- [x] 进度行与聚合节点输入（K_RESULT_PREFIX）不受影响
- [x] `mvn -pl server -am test` 0 失败 0 错误（639 tests, 0 failures, 0 errors, 1 skipped, BUILD SUCCESS）
