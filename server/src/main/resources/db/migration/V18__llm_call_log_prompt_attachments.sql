-- ============================================================
-- V18 - llm_call_log 增加 prompt 装配名单三列
--
-- 记录当次 LLM 调用的 system prompt 中装配的 skill / 工具 / MCP 工具名单
-- （CSV 存储，空为 NULL）。名单是「装配进 prompt 的」而非「模型实际调用的」
-- ——实际调用已有 tool_call_log 台账。
-- 数据来源：AgentChatCaller（经 LlmCallObserver 单点透传，disableTools 时工具列空）；
-- GeneralAssistantAgent / LlmRouteJudge 两个直连构造点暂不采集（落 NULL）。
-- ============================================================
ALTER TABLE llm_call_log
    ADD COLUMN skill_names    TEXT NULL COMMENT 'prompt 装配的技能名单（CSV，空为 NULL）' AFTER error_msg,
    ADD COLUMN tool_names     TEXT NULL COMMENT 'prompt 装配的工具名单（CSV，空为 NULL）' AFTER skill_names,
    ADD COLUMN mcp_tool_names TEXT NULL COMMENT 'prompt 装配的 MCP 工具名单（tool_names 子集，CSV，空为 NULL）' AFTER tool_names;
