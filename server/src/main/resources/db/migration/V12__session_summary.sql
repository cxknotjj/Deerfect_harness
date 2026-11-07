-- 长期记忆滚动摘要（P2-3）：上下文裁剪丢弃的历史轮次压缩为一段摘要，按会话独立存储。
-- covered_message_id 为增量游标：只把该消息 ID 之后新被丢弃的内容并入摘要，避免重复提炼；
-- summary 自身有字符上限（应用侧 app.context.summary-max-chars），滚动合并时由代码截断。
CREATE TABLE `session_summary` (
  `session_id` BIGINT NOT NULL COMMENT '会话主键（session.session_id）',
  `summary` TEXT NOT NULL COMMENT '滚动摘要正文（覆盖 covered_message_id 及更早的历史）',
  `covered_message_id` VARCHAR(64) DEFAULT NULL COMMENT '已纳入摘要的最大快照消息 ID（增量/滚动合并游标）',
  `update_time` DATETIME(3) NOT NULL COMMENT '最近一次摘要生成时间',
  PRIMARY KEY (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话长期记忆滚动摘要';
