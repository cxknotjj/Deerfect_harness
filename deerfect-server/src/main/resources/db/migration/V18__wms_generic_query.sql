-- WMS 工具收敛：十个专用工具 → 一个 wms_query（pack=wms.query）。
--
-- 为什么收敛：wms_search_stock / wms_search_stock_in_orders / wms_get_stock_in_order
-- 这类近义名字挤在工具面里，模型选错的概率随数量上升；而它们本质是同一个动作
-- （按资源查一条只读数据），差异只在 resource 与参数。
--
-- 实现从「wms-mcp-adapter 里的十个 @Tool 方法」换成「tools/http/wms.yaml 的二十个资源」：
-- 端点与参数从代码搬到清单，查询套路（商品名换 id、先列表再详情）写进 skills/wms/SKILL.md。
--
-- 回滚：把 tools 改回原来的十个名字（见 git 历史或 wms-mcp-adapter 的 WmsTools），
-- 并确认 MCP server 已启动。
UPDATE `agent`
SET `tools` = 'wms.query'
WHERE `agent_name` = 'wms';
