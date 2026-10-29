# Tasks

- [x] Task 1: 建任务分支(遵循 create-feature-branch skill)
  - [x] 1.1 前置检查(工作树干净、在 master)→ 创建 `feat/tool-calls-query-endpoint` 分支
- [x] Task 2: 查询端点实现
  - [x] 2.1 新增 `ToolCallController`:`GET /api/tool-calls?sessionId=&serverName=&limit=`,QueryWrapper 组合过滤(id 倒序 + LIMIT),limit 收敛 1~200
  - [x] 2.2 类注释与 `/api/llm-calls` 同风格(说明数据来源与参数语义)
- [x] Task 3: 单测
  - [x] 3.1 新增 `ToolCallControllerTest`(mock ToolCallLogMapper):覆盖 sessionId 过滤、serverName 过滤、limit 边界收敛、无参全量倒序
  - [x] 3.2 验证:`mvn -s .mvn/settings.xml -pl server test` 全绿
- [x] Task 4: 收尾
  - [x] 4.1 HARNESS_TODO.md 勾选「工具调用查询端点」条目
  - [x] 4.2 全量回归 `mvn -s .mvn/settings.xml clean package` 通过;提交 commit(feat 前缀)

# Task Dependencies
- Task 1 → Task 2 → Task 3 → Task 4(单线,改动面小不值得并行)
