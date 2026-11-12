-- 显式记忆（P2-4）：用户明说「记住这个」时落一条，与画像（模型从对话里推断）分离。
--
-- 为什么单独一张表而不是塞进画像文件：来源不同、可信度不同、生命周期不同。
-- 画像是模型推断的软偏好（可被下一次提炼改写），显式记忆是用户声明的事实（只能由用户删）。
-- 软删（deleted_at）保留审计；同 key 写入时旧条目由应用层软删，保证注入不重复。
CREATE TABLE `memory` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户（当前单用户固定 default）',
  `memory_key` VARCHAR(64) DEFAULT NULL COMMENT '可选短标签（车牌/技术栈/生日），同 key 以最新为准',
  `content` VARCHAR(1000) NOT NULL COMMENT '记忆正文（用户声明的事实）',
  `source` VARCHAR(16) NOT NULL COMMENT '来源：user=对话中明示 / cli=命令行 / manual=管理接口',
  `session_id` BIGINT DEFAULT NULL COMMENT '来源会话（仅追溯，不参与过滤——记忆是跨会话的）',
  `created_at` DATETIME(3) NOT NULL COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '最近更新时间',
  `expires_at` DATETIME(3) DEFAULT NULL COMMENT '过期时间（NULL=长期）',
  `deleted_at` DATETIME(3) DEFAULT NULL COMMENT '软删时间（NULL=有效）',
  PRIMARY KEY (`id`),
  KEY `idx_memory_active` (`tenant_id`, `deleted_at`, `expires_at`),
  KEY `idx_memory_key` (`tenant_id`, `memory_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='显式记忆（用户明示，跨会话注入）';

-- 把 memory 组分配给 general / lead：与画像、会话记忆的注入口径一致
-- （子任务专家与聚合角色不注入显式记忆，避免全局事实污染子任务上下文）。
-- 幂等：已含 memory 的行不重复追加。
UPDATE `agent`
SET `tools` = CASE
    WHEN `tools` IS NULL OR TRIM(`tools`) = '' THEN 'memory'
    ELSE CONCAT(`tools`, ', memory')
END
WHERE `agent_name` IN ('general', 'lead')
  AND (`tools` IS NULL OR LOCATE('memory', `tools`) = 0);
