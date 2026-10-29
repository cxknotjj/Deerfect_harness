# Tasks
- [ ] Task 1: V22 迁移——agent 表注册 route-judge 内部行
  - [ ] SubTask 1.1: 新建 `V22__register_route_judge_agent.sql`：INSERT IGNORE route-judge 行
        （is_internal=1、status=1、prompt 搬运 SYSTEM_PROMPT 常量原文、model_provider_id 子查询
        `(SELECT id FROM model_provider WHERE model='qwen3.8-27b')`）
  - [ ] SubTask 1.2: 头注释说明数据依赖（V1 种子的 qwen3.8-27b 行）与幂等语义（uk_agent_name）
- [ ] Task 2: LlmRouteJudge 表驱动化
  - [ ] SubTask 2.1: 构造器注入 `AgentConfigProvider`；新增解析方法：查
        `getAgentConfig("route-judge")` → model/prompt，缺失或异常回退 `ROUTE_MODEL` /
        `SYSTEM_PROMPT` 常量（常量保留，注释标注「回退默认值」）
  - [ ] SubTask 2.2: `judge()` 内解析一次配置，`doCall` 用生效 model/prompt 发起轻量调用
        （轻量客户端 key 跟随生效模型名；丢池逻辑不变）
  - [ ] SubTask 2.3: `record()` 的 promptTokens 估算改用实际生效 prompt
- [ ] Task 3: 单测适配与新增
  - [ ] SubTask 3.1: `LlmRouteJudgeTest` 构造器适配（追加 AgentConfigProvider 参数）
  - [ ] SubTask 3.2: 新增用例：配置命中用库中 model/prompt；无行回退常量；查表异常回退常量
- [ ] Task 4: 端到端验证
  - [ ] SubTask 4.1: 全量 `mvn test` 绿
  - [ ] SubTask 4.2: 重建 jar、重启 8080（先检查端口占用，避免覆盖运行中 fat jar）
  - [ ] SubTask 4.3: 实测：Flyway V22 执行日志、聊天判定行为正常（SIMPLE/COMPLEX 各一发）、
        llm_call_log 落 route-judge 行且带 sessionId、web AgentSelect 下拉无 route-judge

# Task Dependencies
- Task 2 / Task 3 依赖 Task 1 的迁移文件存在（代码回退常量保证无强运行时依赖，可并行开发）
- Task 4 依赖 Task 1-3 全部完成
