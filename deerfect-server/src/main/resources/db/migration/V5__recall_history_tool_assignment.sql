-- V5 - 为 general / lead 分配 recall_history（会话历史归档召回）：
-- general 在 agentic 直答中需要找回窗口外历史；lead 在编排拆解时可能引用早期会话内容。
-- 使用组名 history（ToolAssignments 解析为 RecallHistoryTools），重复执行幂等由应用侧去重。
UPDATE `agent`
SET `tools` = CASE
    WHEN `tools` IS NULL OR TRIM(`tools`) = '' THEN 'history'
    ELSE CONCAT(`tools`, ', history')
END
WHERE `agent_name` IN ('general', 'lead');
