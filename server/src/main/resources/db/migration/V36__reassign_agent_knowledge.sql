-- ============================================================
-- V36 - Agent 知识库按角色职责重新分配：
-- 新增 platform / ops 两个知识库（knowledge/platform、knowledge/ops，
-- git 跟踪，内容为平台真实参数手册与运维手册），原 V29 绑定口径
-- （coder=java、researcher=java,frontend，analyst/writer 刻意留空作
-- 对照组）已不覆盖新库，本迁移按 agent 角色职责全量重排。
--
-- 绑定语义（以 KnowledgeRetriever.parseBinding 为准）：
--   knowledge 逗号分隔 = 绑定的知识库（knowledge/ 一级子目录名）；
--   NULL/空白 = 未绑定，不触发检索；
--   aggregator 在 SKIP_ROLES 硬编码跳过，绑不绑定都不检索；
--   根目录散文档（default 库，如《部署运维指南》）所有 Agent 均可检索，
--   与本列无关。
--
-- 分配矩阵（职责驱动）：
--   general     → 'platform,ops'            默认对话助手：平台参数与运维
--                                             故障问题可溯源回答；
--   researcher  → 'java,frontend,platform,ops'   调研要宽知识面，全库绑定；
--   coder       → 'java,frontend'           代码知识库（补 frontend 组件
--                                             模式，V29 原仅 java）；
--   analyst     → 'ops,platform'            分析素材：运维手册 + 模型矩阵；
--   writer      → 'platform'                交付物写作的产品事实底座；
--   lead        → 'platform,ops'            会话入口 Agent 的 RAG 预取按
--                                             入口绑定生效，拆解任务书时
--                                             引用平台事实；
--   deepseek    → 'ops'                     模型选型矩阵与其身份呼应；
--   aggregator  → NULL                      SKIP_ROLES 硬跳过，材料是子任务
--                                             结果，显式置空表意；
--   route-judge → NULL                      轻量判定调用，不接 RAG；
--   multi-agent → NULL                      模板占位行，非执行体；
--   nailong     → NULL                      QQ 角色扮演，人设纯度优先，
--                                             绝不注入知识库；
--   wms         → NULL                      领域数据一律走 wms_* 工具真实
--                                             查询，绑 platform/ops 会在
--                                             演示中串台（对照知识库测试
--                                             套件的隔离性用例）。
--
-- 勘误 V29 对照组：analyst/writer 的「未绑定对照组」语义由本迁移取消——
-- 多库化后新库 platform/ops 需要真实分配，对照组行为已被知识库测试套件
-- （docs/reports/2026-10-05-knowledge-test-kit.md 的 F 组隔离用例）取代。
--
-- 幂等口径：与 V29 相同——UPDATE 天然幂等，按 uk_agent_name 唯一键精确
-- 匹配；空库上目标行不存在时影响 0 行，静默通过。
-- ============================================================

UPDATE agent SET knowledge = 'platform,ops'          WHERE agent_name = 'general';
UPDATE agent SET knowledge = 'java,frontend,platform,ops' WHERE agent_name = 'researcher';
UPDATE agent SET knowledge = 'java,frontend'         WHERE agent_name = 'coder';
UPDATE agent SET knowledge = 'ops,platform'          WHERE agent_name = 'analyst';
UPDATE agent SET knowledge = 'platform'              WHERE agent_name = 'writer';
UPDATE agent SET knowledge = 'platform,ops'          WHERE agent_name = 'lead';
UPDATE agent SET knowledge = 'ops'                   WHERE agent_name = 'deepseek';
UPDATE agent SET knowledge = NULL                    WHERE agent_name = 'aggregator';
UPDATE agent SET knowledge = NULL                    WHERE agent_name = 'route-judge';
UPDATE agent SET knowledge = NULL                    WHERE agent_name = 'multi-agent';
UPDATE agent SET knowledge = NULL                    WHERE agent_name = 'nailong';
UPDATE agent SET knowledge = NULL                    WHERE agent_name = 'wms';
