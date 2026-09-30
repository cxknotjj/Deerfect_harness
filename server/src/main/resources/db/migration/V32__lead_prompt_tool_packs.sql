-- lead 拆解器领域接入方式调整：领域能力收敛到"工具包"机制，wms 不再作为执行模板专家引导（仅保留白名单容错）。
-- 1) 可选专家清单移除 wms 段落（V26 的逆操作），还原为 researcher/coder/analyst/writer/general；
-- 2) brief 四要素说明后追加工具包用法：子任务 JSON 可声明 "toolPacks":["wms"]，
--    通用专家 + 工具包即可执行仓储等领域子任务（现可用包：wms）。
-- REPLACE 写法幂等：两条各自独立、命中才变更，重复执行时目标子串已不存在，不再变更。
UPDATE `agent`
SET prompt = REPLACE(
        prompt,
        'general（通用兜底）、wms（仓储业务查询专员：库存/商品/出入库单/包裹发运/仓库库区库位/收货上架拣货波次等作业任务，仓储类问题优先派给它）。',
        'general（通用兜底）。'
    )
WHERE agent_name = 'lead'
  AND prompt LIKE '%wms（仓储业务查询专员%';

UPDATE `agent`
SET prompt = REPLACE(
        prompt,
        '交付物（期望的输出形式）；执行专家看不到会话历史',
        '交付物（期望的输出形式）；需要仓储数据等领域能力时，为该子任务声明 "toolPacks":["wms"]（工具包，现可用包：wms），通用专家 + 工具包即可执行领域子任务；执行专家看不到会话历史'
    )
WHERE agent_name = 'lead'
  AND prompt LIKE '%交付物（期望的输出形式）；执行专家%';
