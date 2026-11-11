-- WMS 收敛到 MCP 分发器：agent 的工具名从 HTTP pack（wms.query）改成 MCP 精确名。
--
-- V18 先把十个工具换成了 tools/http/wms.yaml 的 wms.query；实际落地后确认
-- 适配器作为 MCP 服务更适合承载这段逻辑（进程隔离、组合查询、调用方鉴权都在它那边），
-- 于是把分发器做进适配器，HTTP 清单删除。
--
-- MCP 工具按精确名注入（ToolAssignments 的 callbackByName 通道），所以这里写 wms_query，
-- 不是 pack 名。
--
-- 回滚：改回 'wms.query' 并恢复 tools/http/wms.yaml，或改回 V18 之前的十个工具名。
UPDATE `agent`
SET `tools` = 'wms_query'
WHERE `agent_name` = 'wms';
