# Checklist
- [ ] V22 迁移文件存在，INSERT IGNORE 幂等，model_provider_id 子查询引用 model_provider.model='qwen3.8-27b'
- [ ] route-judge 行 prompt 与原 SYSTEM_PROMPT 常量原文一致，is_internal=1、status=1
- [ ] LlmRouteJudge 注入 AgentConfigProvider，judge 用库中 model/prompt；轻量客户端 key 跟随生效模型
- [ ] 配置缺失/查表异常时回退 ROUTE_MODEL / SYSTEM_PROMPT 常量，判定不抛出不阻塞（兜底 SIMPLE 语义不变）
- [ ] record() 的 promptTokens 估算基于实际生效 prompt
- [ ] RouteJudge 接口签名未变，ChatServiceImpl 调用点无需改动
- [ ] LlmRouteJudgeTest 全绿，含「命中走库 / 无行回退 / 异常回退」新用例
- [ ] 全量 mvn test 绿
- [ ] 端到端：V22 执行、判定行为不变、llm_call_log 落 route-judge 行（带 sessionId）、web 下拉无 route-judge
