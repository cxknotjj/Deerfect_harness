-- lead 拆解器的可选专家清单补入 wms 仓储专员（wms-mcp-adapter 接入配套）：
-- 仓储类子任务（库存/商品/出入库单/包裹/库位/作业任务查询）由 lead 指派给 wms，
-- 其持有的 wms_* MCP 工具（WMS MCP 适配服务，只读）才能真正查到数据。
-- REPLACE 写法幂等：重复执行时目标子串已不存在，不再变更。
UPDATE `agent`
SET prompt = REPLACE(
        prompt,
        'general（通用兜底）。',
        'general（通用兜底）、wms（仓储业务查询专员：库存/商品/出入库单/包裹发运/仓库库区库位/收货上架拣货波次等作业任务，仓储类问题优先派给它）。'
    )
WHERE agent_name = 'lead'
  AND prompt LIKE '%general（通用兜底）。%';
