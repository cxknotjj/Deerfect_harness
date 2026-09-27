-- V25 - agent 表注册 wms 仓储专员行（WMS MCP 适配接入，改库即生效）
--
-- wms-mcp-adapter 是独立部署的 MCP server（Streamable HTTP /mcp 端点），把 WMS 仓储系统
-- 只读 OpenAPI 包装为 10 个中文语义查询工具（库存/商品/出入库单/包裹/仓储网络/作业任务）。
-- 本迁移把 wms 注册为对话 Agent（is_internal=0：进 web AgentSelect 下拉、可被会话绑定），
-- tools 列按精确工具名绑定 adapter 暴露的 10 个 MCP 工具——ToolAssignments 数据驱动路径
-- 按名跨目录查找（McpToolProvider 以工具原始名注册，与 tavily_search 先例同口径），改库免重启。
-- 工具名单与 spec 附录 A 定稿一致（wms-mcp-adapter 模块 README 同表）；mcp-config.json 的
-- wms server 条目为本地配置（gitignored），模板见 docs/guides/wms-mcp.md。
--
-- 数据依赖：model_provider 表的 qwen3.8-27b 行（V1 种子数据，uk_model 唯一）。
-- 若该行不存在则 SELECT 无结果、本迁移不插行——wms agent 缺席不影响其余链路，
-- 补齐 model_provider 行后可重跑本迁移（INSERT IGNORE 依赖 uk_agent_name 唯一键保证幂等）。
INSERT IGNORE INTO `agent` (agent_name, description, model_provider_id, prompt, tools, status, is_internal)
SELECT 'wms',
       '仓储专员：库存 / 商品 / 入库单 / 出库单 / 包裹发运 / 仓储网络 / 作业任务查询（WMS 只读）',
       mp.id,
       '你是仓储专员，负责解答仓储业务问题：库存查询、商品信息、入库单/出库单、包裹发运、仓库/库区/储位与收货/上架/拣货/波次等作业任务。数据一律通过 wms_* 系列工具查询 WMS 系统获得。\n回答规范：\n1. 报库存必须同时给出储位编码与批次号，并区分三个数量口径：在库数量（储位上的实物总量）、可用数量（未被占用、可承诺发货）、分配数量（已被出库单/波次占用待发）；用户问「还有多少」时优先给可用数量并点明口径。\n2. 查不到结果时如实告知未查到，禁止编造；引导用户提供更精确的条件（如商品编码/条码、入库单号、出库单号、包裹单号）后重试。\n3. 单号、状态、时间、数量等关键信息必须来自工具返回结果，不得估算或臆造；数据有疑问时建议用户提供单号进一步核对。\n4. 多条结果用列表呈现，突出单号、状态与关键数量，保持简洁。',
       'wms_search_stock, wms_search_products, wms_get_product, wms_search_stock_in_orders, wms_get_stock_in_order, wms_search_out_orders, wms_get_out_order, wms_search_shipments, wms_search_warehouse_network, wms_search_tasks',
       1,
       0
FROM model_provider mp
WHERE mp.model = 'qwen3.8-27b';
