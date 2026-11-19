-- 上下文构成快照持久化：每次真实 LLM 请求一行（只存分组标签与 token 估算，不含 prompt 正文）。
-- 读取口径：内存快照（30 分钟 TTL）优先，未命中回退本表最近一条；历史列表按 session 倒序。
-- 保留期沿用 app.observability.retention-days（默认 90 天），由 ObservabilityLogCleaner 清理。
CREATE TABLE `context_composition_log` (
  `id`                     bigint      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `session_id`             varchar(64) NOT NULL COMMENT '会话ID',
  `agent_name`             varchar(64) NOT NULL COMMENT '调用方角色（general/lead/researcher/aggregator 等）',
  `model`                  varchar(128) DEFAULT NULL COMMENT '实际命中模型的展示名（可空）',
  `turn_id`                varchar(32) DEFAULT NULL COMMENT '轮次标识（编排内各节点同轮共用；可空）',
  `estimated_total_tokens` int         NOT NULL COMMENT '各组成项估算之和（TokenEstimator 口径）',
  `parts`                  json        NOT NULL COMMENT '组成明细 [{key,label,group,tokens,percent}]',
  `created_at`             datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '快照采集时刻',
  PRIMARY KEY (`id`),
  KEY `idx_session_id` (`session_id`, `id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='上下文构成快照（每次真实 LLM 请求一行）';
