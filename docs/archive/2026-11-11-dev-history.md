# dev 历史记录（归档于 2026-11-11）

> 一次性操作与时点记录，不再随代码更新；现行做法见 [dev.md](../dev.md)。

## Flyway 迁移压平（2026-10-30）

- 原 V1\~V43（42 个文件）已合并为单个 `V1__init_schema.sql`（最终 schema + 迁移种子数据）；
  之后从 V2 重新编号。V7–V13 是定时任务 / 推送幂等 / Goal 重投 / 画像增量 / 归档跨会话索引 /
  会话滚动摘要 / 显式记忆，V14 之后按功能继续追加。
- **存量库升级（仅压平当时适用，勿再照做）**：当时先 `DROP TABLE flyway_schema_history;` 再启动，
  让 `baseline-on-migrate` 以 V1 为基线跳过 V1。现在历史表已在正常使用，**再删表会让 Flyway
  对已有库重跑迁移**，切勿执行。若因修改迁移脚本导致校验和不匹配，按
  [project-structure.md](../project-structure.md) 的做法 `flyway repair` 校正 checksum。

## 飞书渠道的八项加固（2026-11-10）

一轮审计后补的，按「不修就静默失效」优先：

1. **绑定时清理**：`FeishuSessionBindingCleaner` 按 session_id 删绑定。缺了它，Web 删会话后
   飞书一直用已删的 id 空转——`appendBatch` 的防复活守卫会静默跳过写回，表现是「突然失忆」。
2. **长连接健康**：`FeishuWsHealth`（channelId `feishu-ws`）回答「能不能收到消息」，
   与 webhook 的「能不能发出去」分开。判据只有「运行中 + 最近有事件」，7 天无事件记 UNKNOWN。
3. **池满必回话**：聊天池拒绝时回一句「消息有点多」，否则用户看到的是永久静默。
4. **回复上限**：`reply-max-chars` 默认 3000，超长截断并标注，避免整条因体积超限发不出去。
5. **表情 key 缓存**：`image_key` 在租户内可复用，按索引名缓存，省掉每次上传。
6. **重试与令牌刷新**：401 / 429 / 5xx 重试一次；401 先清 token 缓存。
7. **处理回执**：慢轮次给原消息贴 `OnIt` 表情，`finally` 里撤销；快速回复零调用。
8. **`/status`**：新增 `contract.api.HarnessStatusPort`（server 实现复用监控页汇总），
   飞书指令输出会话 + 组件状态。端口必须只读，不得触发探活。
