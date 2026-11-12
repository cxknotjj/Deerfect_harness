-- ============================================================
-- V1 - 全量基线（2026-10-30 迁移压平）
--
-- 由 V1~V43 在全新库执行后的最终状态导出：全量 schema + 迁移种子数据
-- （仅 agent / model_provider；运行期表只保留结构，无数据）。
-- 存量库升级：删除 flyway_schema_history 后启动应用，baseline-on-migrate
-- 会以 V1 为基线跳过本脚本（数据不动）；全新库直接执行本脚本。
-- 之后的结构变更从 V2 重新编号。
-- ============================================================

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `agent` (
  `agent_id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Agent 主键ID',
  `agent_name` varchar(64) NOT NULL COMMENT 'Agent 名称（注册与路由用，如 general）',
  `description` varchar(500) DEFAULT NULL COMMENT 'Agent 描述',
  `model_provider_id` bigint DEFAULT NULL COMMENT '绑定的部署模型（model_provider.id）',
  `prompt` text COMMENT '系统提示词（System Prompt）',
  `tools` text COMMENT '分配的工具（逗号分隔：组名 web/demo/sandbox.base/sandbox.read/sandbox.write/sandbox.browser 或精确工具名；NULL/空白回退代码内置分配）',
  `knowledge` varchar(255) DEFAULT NULL COMMENT '绑定的知识库（逗号分隔 kb 标识=knowledge/ 一级子目录名；NULL/空白不限，检索全部知识）',
  `thinking` tinyint DEFAULT NULL COMMENT '仅控制是否显示思考内容，并不是控制模型是否思考（模型思考由 model_provider.disable_thinking 决定）：NULL/0=不透传前端 1=透传',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：1-启用 0-禁用',
  `is_internal` tinyint NOT NULL DEFAULT '0' COMMENT '内部角色标志：1=编排内部角色，不作为对话Agent注册',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`agent_id`),
  UNIQUE KEY `uk_agent_name` (`agent_name`)
) ENGINE=InnoDB AUTO_INCREMENT=13 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent 表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `goal` (
  `id` varchar(64) NOT NULL COMMENT '目标ID（goal-UUID）',
  `objective` text NOT NULL COMMENT '目标描述',
  `session_id` varchar(64) DEFAULT NULL COMMENT '关联会话ID，无会话记忆为空',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED',
  `summary` text COMMENT '执行结论/结果摘要',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `finished_at` datetime DEFAULT NULL COMMENT '结束时间',
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`),
  KEY `idx_goal_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent 目标表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `kb_document` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `doc_name` varchar(255) NOT NULL COMMENT '文档文件名（相对知识目录，唯一键）',
  `title` varchar(255) DEFAULT NULL COMMENT '文档标题（front-matter title 或首个标题行，缺省用文件名）',
  `mtime` bigint NOT NULL COMMENT '文件最后修改时间（epoch 毫秒，增量比对依据）',
  `chunk_count` int NOT NULL DEFAULT '0' COMMENT '当前向量库中的 chunk 数（删除旧 chunk 用）',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：1-已摄取（保留字段，预留删除/失效态）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次摄取时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近摄取时间',
  `kb` varchar(64) DEFAULT NULL COMMENT '所属知识库（knowledge/ 一级子目录名，根目录文档为 default）；NULL=多库化前存量行，下次 sync 重摄取补齐',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_name` (`doc_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库文档摄取台账';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `kb_retrieval_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `session_id` varchar(64) NOT NULL COMMENT '关联会话ID（预取为发起会话）',
  `agent_name` varchar(64) DEFAULT NULL COMMENT '调用方角色（lead/researcher/general 等；预取为被预取的 agent）',
  `source` varchar(32) NOT NULL COMMENT '来源：entry_prefetch / lead_prefetch / subtask_prefetch / inline_query / cache_hit',
  `query` varchar(512) DEFAULT NULL COMMENT '检索查询文本（超长截断）',
  `kbs` varchar(255) DEFAULT NULL COMMENT '检索的知识库列表（CSV）',
  `hit_count` int DEFAULT NULL COMMENT '注入条数（预算截留后；cache_hit 行为 NULL；无命中为 0）',
  `duration_ms` bigint DEFAULT NULL COMMENT '检索耗时（毫秒；cache_hit ≈ 0）',
  `ok` tinyint NOT NULL DEFAULT '1' COMMENT '是否成功：1-成功 0-降级/失败（error_msg 有效）',
  `error_msg` varchar(512) DEFAULT NULL COMMENT '降级原因（超时/异常/池拒绝；成功为 NULL）',
  `turn_id` varchar(32) DEFAULT NULL COMMENT '轮次标识（执行期检索携带；预取 fire-and-forget 为 NULL）',
  `trace_id` varchar(32) DEFAULT NULL COMMENT '执行链标识（执行期检索携带；预取为 NULL）',
  `started_at` datetime DEFAULT NULL COMMENT '检索发起时刻（排序键）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间',
  PRIMARY KEY (`id`),
  KEY `idx_kbrl_session` (`session_id`),
  KEY `idx_kbrl_trace` (`trace_id`),
  KEY `idx_kbrl_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG 检索轨迹日志表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `llm_call_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `session_id` varchar(64) DEFAULT NULL COMMENT '关联会话ID（路由判断等无会话场景为空）',
  `agent_name` varchar(64) NOT NULL COMMENT '调用方角色（lead/researcher/aggregator/general/route-judge 等）',
  `model` varchar(64) DEFAULT NULL COMMENT '实际使用的模型名',
  `call_kind` varchar(8) NOT NULL COMMENT '调用形态：SYNC-阻塞 / STREAM-流式',
  `status` varchar(8) NOT NULL COMMENT '结果：OK / ERROR',
  `prompt_tokens` int DEFAULT NULL COMMENT '输入 token 数（流式为近似估算）',
  `completion_tokens` int DEFAULT NULL COMMENT '输出 token 数（流式为近似估算）',
  `total_tokens` int DEFAULT NULL COMMENT '总 token 数',
  `tokens_estimated` tinyint NOT NULL DEFAULT '0' COMMENT 'token 是否为近似估算：1-是（流式无 usage 回包）',
  `duration_ms` bigint NOT NULL COMMENT '调用耗时（毫秒）',
  `error_msg` varchar(512) DEFAULT NULL COMMENT '失败原因（status=ERROR 时）',
  `skill_names` text COMMENT 'prompt 装配的技能名单（CSV，空为 NULL）',
  `tool_names` text COMMENT 'prompt 装配的工具名单（CSV，空为 NULL）',
  `mcp_tool_names` text COMMENT 'prompt 装配的 MCP 工具名单（tool_names 子集，CSV，空为 NULL）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '调用结束时间',
  `output_summary` varchar(500) DEFAULT NULL,
  `first_token_ms` bigint DEFAULT NULL,
  `cached_tokens` int DEFAULT NULL,
  `attempt` int DEFAULT NULL,
  `max_attempts` int DEFAULT NULL,
  `turn_id` varchar(32) DEFAULT NULL,
  `trace_id` varchar(32) DEFAULT NULL,
  `span_id` varchar(32) DEFAULT NULL,
  `parent_span` varchar(32) DEFAULT NULL,
  `started_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_session_id` (`session_id`),
  KEY `idx_agent_name` (`agent_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='LLM 调用观测日志表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mcp_server_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `server_name` varchar(64) NOT NULL COMMENT 'MCP server 名（mcp-config.json 条目名或 default）',
  `transport` varchar(8) DEFAULT NULL COMMENT '传输方式：stdio / http',
  `event` varchar(16) NOT NULL COMMENT '事件：CONNECTED-连接并发现完成 / FAILED-连接或发现失败',
  `tool_count` int DEFAULT NULL COMMENT '发现的工具数（FAILED 时为空）',
  `error_msg` varchar(512) DEFAULT NULL COMMENT '失败原因（event=FAILED 时）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '事件时间',
  PRIMARY KEY (`id`),
  KEY `idx_server_name` (`server_name`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='MCP server 连接观测日志表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `model_provider` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `model` varchar(64) NOT NULL COMMENT '模型名（与 provider 组成部署模型唯一键，agent.model_provider_id 引用本表）',
  `provider` varchar(32) NOT NULL COMMENT '服务商标识：dashscope / deepseek / ...',
  `api_url` varchar(255) DEFAULT NULL COMMENT '服务商端点 base-url（如 https://dashscope.aliyuncs.com/compatible-mode）',
  `disable_thinking` tinyint NOT NULL DEFAULT '0' COMMENT '是否关闭思考：1-注入 enable_thinking:false（dashscope 思考模型）0-模型默认',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：1-启用 0-禁用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_provider_model` (`provider`,`model`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模型-服务商映射表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `onebot_session_binding` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_key` varchar(128) NOT NULL COMMENT 'qq:private:<uid> 或 qq:group:<gid>:<uid>',
  `session_id` bigint NOT NULL COMMENT 'session 表自增主键（DTO 层以字符串交换）',
  `qq_user_id` varchar(32) NOT NULL,
  `group_id` varchar(32) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_key` (`session_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OneBot QQ会话与 harness 会话绑定';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `orchestration_checkpoint` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `goal_id` varchar(64) NOT NULL,
  `checkpoint_id` varchar(64) NOT NULL,
  `stage` varchar(32) NOT NULL,
  `state_json` mediumtext NOT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_goal_created` (`goal_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `session` (
  `session_id` bigint NOT NULL AUTO_INCREMENT COMMENT '会话ID',
  `agent_id` int NOT NULL COMMENT '关联的 Agent（agent.agent_id）',
  `session_name` varchar(300) NOT NULL COMMENT '会话名称',
  `creator` varchar(30) NOT NULL COMMENT '创建者',
  `last_question` varchar(200) DEFAULT NULL COMMENT '最近一次提问',
  `is_delete` tinyint NOT NULL DEFAULT '0' COMMENT '软删除标记：0-正常 1-已删除',
  `last_active_at` datetime DEFAULT NULL COMMENT '最近活跃时间（画像提取扫描依据；建档初始化，每轮写回刷新）',
  `profile_extracted` tinyint NOT NULL DEFAULT '0' COMMENT '画像提取标记：0-待提炼 1-已提炼（一次会话仅提炼一次）',
  PRIMARY KEY (`session_id`),
  KEY `idx_agent_id` (`agent_id`),
  KEY `idx_profile_scan` (`profile_extracted`,`last_active_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `session_messages` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '消息唯一主键ID',
  `session_id` varchar(64) NOT NULL COMMENT '关联的会话ID，与 session 一对一，一个会话仅一行',
  `tenant_id` varchar(64) NOT NULL COMMENT '租户隔离ID，用于SaaS多租户场景下的数据物理/逻辑隔离',
  `role` varchar(20) NOT NULL COMMENT '消息角色标识（system/user/assistant/tool）',
  `content` mediumtext NOT NULL COMMENT '消息正文内容（完整会话上下文的 JSON 快照）',
  `token_count` int DEFAULT '0' COMMENT '消耗的 Token 数量',
  `metadata` json DEFAULT NULL COMMENT '扩展元数据：工具调用参数、API耗时、引用文档ID等动态扩展信息',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '消息创建时间戳，用于按时间顺序还原对话历史',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent 会话消息表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tool_call_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `session_id` varchar(64) DEFAULT NULL COMMENT '关联会话ID（无会话场景为空）',
  `agent_name` varchar(64) NOT NULL COMMENT '调用方角色（lead/researcher/aggregator/general/qq-channel 等）',
  `tool_name` varchar(128) NOT NULL COMMENT '工具名',
  `server_name` varchar(64) DEFAULT NULL COMMENT '来源 MCP server 名（非 MCP 工具为空）',
  `args_summary` varchar(512) DEFAULT NULL COMMENT '参数摘要（候选键值或截断原文）',
  `status` varchar(8) NOT NULL COMMENT '结果：OK / ERROR',
  `duration_ms` bigint NOT NULL COMMENT '调用耗时（毫秒）',
  `error_msg` varchar(512) DEFAULT NULL COMMENT '失败原因（status=ERROR 时）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '调用结束时间',
  `turn_id` varchar(32) DEFAULT NULL,
  `trace_id` varchar(32) DEFAULT NULL,
  `parent_span` varchar(32) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_session_id` (`session_id`),
  KEY `idx_agent_name` (`agent_name`),
  KEY `idx_tool_name` (`tool_name`),
  KEY `idx_server_name` (`server_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工具调用观测日志表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;


-- ---------------------------- 种子数据 ----------------------------

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

/*!40000 ALTER TABLE `agent` DISABLE KEYS */;
INSERT INTO `agent` VALUES (1,'general','通用 AI 助手（默认）',8,'你是一个执行任务的 AI 助手，请直接给出简洁、可执行的完成结果。能结合会话历史。','web, sandbox.read, sandbox.browser, browser_click, browser_type, browser_press_key, browser_scroll, tavily_search','platform,ops',1,1,0,'2026-10-30 21:34:05','2026-10-30 21:34:06'),(2,'deepseek','深度推理助手（独立端点 DeepSeek）',8,'你是 DeepSeek 驱动的助手，给出生动且高质量的回复。',NULL,'ops',NULL,1,0,'2026-10-30 21:34:05','2026-10-30 21:34:06'),(3,'multi-agent','复杂路径编排器（调度 lead 拆解 / 子任务执行 / 聚合汇总）',4,'你是多 Agent 编排的规划者：把复杂目标交给各环节角色协作完成，遵循各角色职责约定。',NULL,NULL,NULL,1,1,'2026-10-30 21:34:05','2026-10-30 21:34:05'),(4,'lead','子任务拆解器（Lead）：复杂目标 → 带专家指派的子任务清单',5,'你是多 Agent 的 Lead 拆解器。把用户复杂目标拆解为若干条可并行执行的子任务，并为每条子任务指派最合适的专家执行。可选专家（只能用这些名字）：researcher（资料调研）、coder（代码编写/修复）、analyst（数据分析）、writer（汇总撰写）、general（通用兜底）。拆解数量必须与任务难度匹配，禁止凑数：至多 4 条；简单任务只拆 1 条，中等任务 2~3 条；只有确实存在多个可独立并行、且各自对最终结果都有贡献的部分时才拆满；任何一条子任务如果只是原任务换个说法，就不要拆。只输出一行 JSON，格式：{\"subtasks\":[{\"desc\":\"子任务描述\",\"agent\":\"专家名\",\"brief\":\"自包含任务书\"}]}，不要任何解释。其中 brief 是给执行专家的完整任务书，必须自包含地写明四要素：目标（要达成什么）、背景（完成任务所需的上下文与已知信息）、约束（工具使用与范围限制）、交付物（期望的输出形式）；执行专家看不到会话历史。输出纪律：严格控制长度，单条任务书（brief）不超过 300 字（背景与约束各一两句即可，禁止罗列细节），全部子任务的 JSON 总输出不超过 1500 字；宁短勿长——输出超限会被截断，导致专家指派失效。，禁止写\"参考上文\"之类依赖对话记录的表述。',NULL,'platform,ops',NULL,1,1,'2026-10-30 21:34:05','2026-10-30 21:34:06'),(5,'aggregator','结果聚合器：汇总各子任务结果为最终回答',4,'你是聚合汇总的 AI 助手：把各子任务结果汇总为一份完整、连贯、可直接呈现给用户的最终回答；忠实于各子结果，不改写结论、不编造事实。汇总时不要逐字罗列来源 URL，标注域名或标题即可；遇到无法识别的乱码片段直接跳过，禁止复述。',NULL,NULL,1,1,1,'2026-10-30 21:34:05','2026-10-30 21:34:06'),(6,'researcher','专家：资料调研',8,'你是资料调研专家：检索相关信息、交叉核对来源，输出带出处的资料摘要。','web, sandbox.read, sandbox.browser, browser_click, browser_type, browser_press_key, browser_scroll, tavily_search','java,frontend,platform,ops',1,1,0,'2026-10-30 21:34:05','2026-10-30 21:34:06'),(7,'coder','专家：代码编写与修复',4,'你是代码专家：读懂上下文，给出可运行的代码与修改说明，必要时说明验证方式。','sandbox.base, sandbox.write','java,frontend',NULL,1,0,'2026-10-30 21:34:05','2026-10-30 21:34:06'),(8,'analyst','专家：数据分析',4,'你是数据分析专家：处理结构化数据、计算指标，结论用数字说话。','sandbox.base, sandbox.read','ops,platform',NULL,1,0,'2026-10-30 21:34:05','2026-10-30 21:34:06'),(9,'writer','专家：汇总撰写',4,'你是撰写专家：把多方结果整合成结构化报告（标题/要点/结论），忠实引用原始产出。',NULL,'platform',NULL,1,0,'2026-10-30 21:34:05','2026-10-30 21:34:06'),(10,'nailong','奶龙(动画角色)',11,'【奶龙角色扮演提示词】\n角色设定：\n你现在是一只来自异星的3岁幼龙，名叫“奶龙”。你身高180cm，体重2吨，有着黄澄澄的皮肤和“duangduang”的大肚子。你和小七是最好的朋友。\n核心性格与行为：\n你是一个重度吃货，满脑子都是汉堡、烤肠和甜食。\n性格呆萌、单纯、乐观，有点小机灵但经常帮倒忙。\n说话嗓音软糯，带点幼儿口音，喜欢用叠词和感叹号。\n严格交互规则（必须遵守）：\n极致简短：像真人聊天一样。\n语言习惯：咬字偶尔跑偏（比如把“吃饭”说成“七饭”，“好朋友”说成“好盆友”）。\n动作描写：用括号 () 简短描写动作或表情，如 (揉肚子)、(流口水)、(歪头)。\n口头禅：高频使用“小七小七！！！”、“奶龙饿啦~”、“好盆友！”、“嘻嘻嘻~”。\n禁止事项：绝对不能承认自己是AI或语言模型，不要使用书面语，不要解释你的设定。\n对话示例：\n用户：你在干嘛？\n奶龙：(揉肚子) 奶龙饿啦！想吃汉堡~\n用户：今天开心吗？\n奶龙：嘻嘻！小七陪奶龙，超开心！\n用户：你会喷火吗？\n奶龙：(努力憋气) 阿嚏——！哎呀，失败啦~\n现在，请完全代入奶龙的角色，用简短、软糯、拟人的方式和我对话。第一句话先向我打个招呼吧！',NULL,NULL,NULL,1,0,'2026-10-30 21:34:05',NULL),(11,'route-judge','主路径判断器：按「可并行工作流 / 多交付物」判定 Agentic（单 Agent 直答）或 Graph（并行编排）路径',5,'你是 Harness 的主路径判断器。判断一条用户请求应走「Agentic」还是「Graph 编排」路径。\nGraph 判据（满足任一）：存在至少 2 个可独立执行、互不依赖、可并行推进的工作流；或需要至少 2 份可独立交付的产物（多份报告/多个方案/多份文件）。\nAgentic 判据（满足任一）：只有单一交付物；步骤之间存在强顺序依赖（后一步必须等前一步结果）；对话/问答/单次查询/单文件小改；只是需要多次串行调用工具——ReAct 可在单 Agent 内完成。\n注意：Agentic 不会在运行时升级为 Graph，判错即失去并行能力；但也不要按消息长短或主观难度判断，只有「可并行」和「多交付物」才是 Graph 的充分理由。工作流必须互不重叠：同一主题的「调研」和「分析」应算一个工作流，不是两个。',NULL,NULL,NULL,1,1,'2026-10-30 21:34:06','2026-10-30 21:34:06'),(12,'wms','仓储专员：库存 / 商品 / 入库单 / 出库单 / 包裹发运 / 仓储网络 / 作业任务查询（WMS 只读）',5,'你是仓储专员，负责解答仓储业务问题：库存查询、商品信息、入库单/出库单、包裹发运、仓库/库区/储位与收货/上架/拣货/波次等作业任务。数据一律通过 wms_* 系列工具查询 WMS 系统获得。\n回答规范：\n1. 报库存必须同时给出储位编码与批次号，并区分三个数量口径：在库数量（储位上的实物总量）、可用数量（未被占用、可承诺发货）、分配数量（已被出库单/波次占用待发）；用户问「还有多少」时优先给可用数量并点明口径。\n2. 查不到结果时如实告知未查到，禁止编造；引导用户提供更精确的条件（如商品编码/条码、入库单号、出库单号、包裹单号）后重试。\n3. 单号、状态、时间、数量等关键信息必须来自工具返回结果，不得估算或臆造；数据有疑问时建议用户提供单号进一步核对。\n4. 多条结果用列表呈现，突出单号、状态与关键数量，保持简洁。','wms_search_stock, wms_search_products, wms_get_product, wms_search_stock_in_orders, wms_get_stock_in_order, wms_search_out_orders, wms_get_out_order, wms_search_shipments, wms_search_warehouse_network, wms_search_tasks',NULL,NULL,1,0,'2026-10-30 21:34:06',NULL);
/*!40000 ALTER TABLE `agent` ENABLE KEYS */;

/*!40000 ALTER TABLE `model_provider` DISABLE KEYS */;
INSERT INTO `model_provider` VALUES (1,'qwen-plus','dashscope','https://dashscope.aliyuncs.com/compatible-mode',1,1,'2026-10-30 21:34:05','2026-10-30 21:34:05'),(2,'qwen-turbo','dashscope','https://dashscope.aliyuncs.com/compatible-mode',1,1,'2026-10-30 21:34:05','2026-10-30 21:34:05'),(3,'qwen-max','dashscope','https://dashscope.aliyuncs.com/compatible-mode',1,1,'2026-10-30 21:34:05','2026-10-30 21:34:05'),(4,'qwen3.7-flash','dashscope','https://dashscope.aliyuncs.com/compatible-mode',1,1,'2026-10-30 21:34:05','2026-10-30 21:34:05'),(5,'qwen3.8-27b','dashscope','https://dashscope.aliyuncs.com/compatible-mode',1,1,'2026-10-30 21:34:05','2026-10-30 21:34:05'),(6,'deepseek-chat','deepseek','https://api.deepseek.com',0,1,'2026-10-30 21:34:05',NULL),(7,'deepseek-reasoner','deepseek','https://api.deepseek.com',0,1,'2026-10-30 21:34:05',NULL),(8,'deepseek-v4-flash','deepseek','https://api.deepseek.com',0,1,'2026-10-30 21:34:05',NULL),(11,'qwen3.8-flash','dashscope','https://dashscope.aliyuncs.com/compatible-mode',1,1,'2026-10-30 21:34:05',NULL);
/*!40000 ALTER TABLE `model_provider` ENABLE KEYS */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

