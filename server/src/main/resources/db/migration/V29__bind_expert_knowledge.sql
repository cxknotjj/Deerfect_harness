-- ============================================================
-- V29 - 专家测试知识库绑定（编排子任务 RAG 预取的端到端测试）：
-- 给编排专家 agent 行填 knowledge 列（V12 加列，逗号分隔 kb 标识 =
-- knowledge/ 一级子目录名；NULL/空白 = 未绑定，不触发检索），
-- 使 RagPrefetcher 子任务预取 → 专家节点 buildKnowledgeBlock 链路可真实命中。
--
-- 勘误：V12 列 COMMENT 写的「NULL/空白不限，检索全部知识」为历史笔误，
-- 实际语义以 KnowledgeRetriever.parseBinding 为准：NULL/空白 = 未绑定，不检索。
-- V12 已被 flyway 应用、改动会破坏 checksum 校验，故在此勘误不改原文件。
--
-- 绑定口径（覆盖单库 / 多库两种形态）：
--   coder      → 'java'          单库绑定；
--   researcher → 'java,frontend' 多库绑定。
-- knowledge/ 已有现成子库 java/、frontend/（git 跟踪），无需新建库与文档。
--
-- 对照组设计：analyst / writer 刻意保持未绑定（本迁移不触碰其行），
-- 作为「未绑定专家零行为」的运行时对照组——预取解析 kb 绑定为 null，
-- 不发起任何检索，行为与现状一致；general / wms / lead 等其余行同样不受影响。
--
-- 幂等口径：UPDATE 天然幂等——重复执行把目标行写成相同值，结果确定，
-- 不产生重复行、不报错；按 agent_name（uk_agent_name 唯一键）精确匹配。
--
-- 数据依赖：不依赖其他迁移的数据。空库上 agent 表无 coder/researcher 行时
-- UPDATE 影响 0 行，静默通过不报错；行存在与否不影响其余链路。
-- ============================================================

UPDATE agent SET knowledge = 'java' WHERE agent_name = 'coder';

UPDATE agent SET knowledge = 'java,frontend' WHERE agent_name = 'researcher';
