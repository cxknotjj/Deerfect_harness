-- 主动投递 outbox（M1）：每条外发消息有记录、有终态，失败可重投、可审计。
--
-- 为什么需要这张表：改造前 ScheduleServiceImpl.deliver() 无论发送成败都 markNotified()，
-- 一次网络抖动就等于这条日报永远消失，日志里只有一行 WARN。有了 outbox，
-- 「上周那条日报到底发没发」有据可查，失败也能按退避自动重投。
--
-- 几个刻意的设计：
-- 1. 不设 SENDING 状态：用 next_retry_at 推到未来充当领取租约，少一个状态就少一类
--    「卡在 SENDING」的脏数据（进程在发送途中被杀时，租约到期会自动重试）。
-- 2. channel_chain 存原文而非拆表：链最多 2~3 跳，拆表会让「看一条消息的完整投递过程」
--    变成 join。原文 + chain_index 足够定位。
-- 3. content 存全文：消息量极小（个人 agent 每天几条到几十条），换取「重投时不回查
--    业务表」和「事后核对原文」。
CREATE TABLE `delivery_outbox` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户（当前单用户固定 default）',
  `source` VARCHAR(32) NOT NULL COMMENT '来源：schedule=定时任务 / alert=告警 / manual=手工',
  `ref_id` VARCHAR(64) DEFAULT NULL COMMENT '业务指针（schedule.id / goal.id），仅追溯用',
  `channel_chain` VARCHAR(512) NOT NULL COMMENT '降级链原文，如 feishu:main|email:me@example.com',
  `chain_index` INT NOT NULL DEFAULT 0 COMMENT '当前尝试到链上第几个通道（0 起）',
  `content` TEXT NOT NULL COMMENT '消息正文（重投时不再回查业务表）',
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING=待投 / SENT=已送达 / DEAD=重投耗尽',
  `attempts` INT NOT NULL DEFAULT 0 COMMENT '总尝试次数（跨通道累加，发送前递增，仅供审计）',
  `hop_attempts` INT NOT NULL DEFAULT 0 COMMENT '当前跳的尝试次数（决定退避与链推进；换跳时归零）',
  `last_error` VARCHAR(512) DEFAULT NULL COMMENT '最后一次失败原因（含渠道原始错误码）',
  `next_retry_at` DATETIME(3) DEFAULT NULL COMMENT '下次可尝试时刻，兼作领取租约',
  `sent_at` DATETIME(3) DEFAULT NULL COMMENT '送达时刻',
  `created_at` DATETIME(3) NOT NULL COMMENT '入队时刻',
  PRIMARY KEY (`id`),
  KEY `idx_outbox_due` (`status`, `next_retry_at`),
  KEY `idx_outbox_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主动投递 outbox（失败可重投、可审计）';
