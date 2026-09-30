-- agent 表新增 thinking 列：思考内容显示开关（按 agent 逐行声明，与 knowledge/tools 列同构）。
-- 语义口径：仅控制是否显示思考内容，并不是控制模型是否思考——
-- 模型思考与否由 model_provider.disable_thinking 端点配置决定（ThinkingSwitchChatModel 端点级注入）。
-- NULL/0 = 不透传前端；1 = 该 agent 调用的思考增量经 ProgressLine("思考"/"思考N") 透传 web 轨迹。
ALTER TABLE `agent`
    ADD COLUMN `thinking` TINYINT NULL DEFAULT NULL
    COMMENT '仅控制是否显示思考内容，并不是控制模型是否思考（模型思考由 model_provider.disable_thinking 决定）：NULL/0=不透传前端 1=透传'
    AFTER `knowledge`;

-- 初始显示范围：general（主回答直答）、researcher（子任务长思考；qwen3.8-max 端点 disable_thinking=0 默认思考）。
-- 其余行保持 NULL，后续按需 UPDATE（如聚合模型实际思考时置 aggregator 行）。
UPDATE `agent` SET `thinking` = 1 WHERE `agent_name` IN ('general', 'researcher');
