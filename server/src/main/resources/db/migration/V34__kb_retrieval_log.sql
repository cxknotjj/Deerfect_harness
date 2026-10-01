-- RAG 检索轨迹日志表（spec: add-kb-retrieval-log）：预取/现查/短路/降级全事件落行，
-- 补齐「预取是否生效、命中短路是否发生、注入几条、降级几次」的观测盲区。
-- 数据来源：KnowledgeRetriever（执行期检索 + 预取缓存短路）/ RagPrefetcher（三提交点预取）
-- 统一经 KbRetrievalRecorder 异步写入；观测失败不影响检索与编排主链路。
-- 注：spec 初稿写的 V33 已被 agent thinking 列占用，本迁移顺延为 V34。
CREATE TABLE kb_retrieval_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    session_id  VARCHAR(64)  NOT NULL COMMENT '关联会话ID（预取为发起会话）',
    agent_name  VARCHAR(64)  NULL COMMENT '调用方角色（lead/researcher/general 等；预取为被预取的 agent）',
    source      VARCHAR(32)  NOT NULL COMMENT '来源：entry_prefetch / lead_prefetch / subtask_prefetch / inline_query / cache_hit',
    query       VARCHAR(512) NULL COMMENT '检索查询文本（超长截断）',
    kbs         VARCHAR(255) NULL COMMENT '检索的知识库列表（CSV）',
    hit_count   INT          NULL COMMENT '注入条数（预算截留后；cache_hit 行为 NULL；无命中为 0）',
    duration_ms BIGINT       NULL COMMENT '检索耗时（毫秒；cache_hit ≈ 0）',
    ok          TINYINT      NOT NULL DEFAULT 1 COMMENT '是否成功：1-成功 0-降级/失败（error_msg 有效）',
    error_msg   VARCHAR(512) NULL COMMENT '降级原因（超时/异常/池拒绝；成功为 NULL）',
    turn_id     VARCHAR(32)  NULL COMMENT '轮次标识（执行期检索携带；预取 fire-and-forget 为 NULL）',
    trace_id    VARCHAR(32)  NULL COMMENT '执行链标识（执行期检索携带；预取为 NULL）',
    started_at  DATETIME     NULL COMMENT '检索发起时刻（排序键）',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间',
    PRIMARY KEY (id),
    KEY idx_kbrl_session (session_id),
    KEY idx_kbrl_trace (trace_id),
    KEY idx_kbrl_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG 检索轨迹日志表';
