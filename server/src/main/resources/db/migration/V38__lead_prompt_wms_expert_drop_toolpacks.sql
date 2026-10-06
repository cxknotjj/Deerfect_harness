-- ============================================================
-- V38 - lead prompt 移除具体工具名/工具包指令
--
-- 背景：analyst 等 generic 专家可执行 wms_* MCP 工具的复盘结论——prompt 中
-- 「需要仓储数据时声明 toolPacks:["wms"]，通用专家 + 工具包执行领域子任务」的
-- 指令写死了具体包名/工具语义，且当时执行期无分配边界校验，lead 由此越权分发
-- 自己并不持有的工具。
--
-- 分配边界口径（数据驱动）：lead 可分配的工具 ⊆ 其 agent 表 tools 列声明——
-- 执行期 resolvePacks 将包内工具逐个与 lead 行 tools 列精确名比对，列外 warn 丢弃
-- （越权零授出）。lead 行 tools 列当前为 NULL → 零授出，wms_* 等领域工具独属
-- 对应专家行（由专家自身 tools 列经 ToolAssignments.forAgent 授予，与 lead 无关）。
-- 提示词因此不携带任何具体工具名/包名；未来若需开放 lead 分发，改 lead 行 tools
-- 列即可生效，无需改 prompt（避免 prompt 与数据漂移）。
--
-- REPLACE 写法幂等：重复执行时目标子串已不存在，LIKE 守卫不再命中，零变更。
-- ============================================================

UPDATE `agent`
SET prompt = REPLACE(
        prompt,
        '；需要仓储数据等领域能力时，为该子任务声明 "toolPacks":["wms"]（工具包，现可用包：wms），通用专家 + 工具包即可执行领域子任务；执行专家看不到会话历史。',
        '；执行专家看不到会话历史。'
    )
WHERE agent_name = 'lead'
  AND prompt LIKE '%toolPacks%';
