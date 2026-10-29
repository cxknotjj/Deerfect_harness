# Tasks

- [x] Task S1: Tavily 405 降噪（application.yaml logging.level 一行 + 注释）
- [x] Task S2: V24 迁移（画像扫描索引 + 观测表 created_at 索引）+ ObservabilityLogCleaner
      （@Scheduled 分批删除，retention-days 0=禁用）+ 单测
- [x] Task S3: OneBotEventServiceImpl 删内部 8 线程池改用 onebotExecutor；cancel(true)
- [x] Task S4: ApiTokenFilter（X-API-Token 常量时间比较，/onebot/** 豁免，空配置不启用）
      + web client.ts 统一加头 + Filter 单测
- [x] Task W1: fetchPage / useAgents.load catch + 轻提示回调
- [x] Task W2: 会话项键盘可达（tabindex/role/Enter/Space/aria-label）
- [x] Task W3: web 小修一批（isAbortError 去重、\r\n 容错、build 串 typecheck、CSS 杂项）

# Task Dependencies
- 各 Task 相互独立；每项完成后立即测试并独立 commit
- 服务端每项跑 `mvn -pl server -am test`；web 每项跑 `npm run build`
