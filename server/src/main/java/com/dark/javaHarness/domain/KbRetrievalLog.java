package com.dark.javaHarness.domain;

import java.time.LocalDateTime;

/**
 * 一次 RAG 检索的观测记录（不可变值对象，由 KnowledgeRetriever/RagPrefetcher 组装、
 * KbRetrievalRecorder 落库），补齐「预取是否生效、命中短路是否发生、注入几条、降级几次」盲区。
 *
 * @param sessionId  关联会话ID（预取为发起会话）
 * @param agentName  调用方角色（lead/researcher/general 等；预取为被预取的 agent）
 * @param source     来源：entry_prefetch / lead_prefetch / subtask_prefetch / inline_query / cache_hit
 * @param query      检索查询文本（落库截断 512）
 * @param kbs        检索的知识库列表（CSV 化落库）
 * @param hitCount   注入条数（预算截留后；cache_hit 为 null；无命中为 0）
 * @param durationMs 检索耗时（毫秒；cache_hit ≈ 0）
 * @param ok         true-成功，false-降级/失败（errorMsg 有效）
 * @param errorMsg   降级原因（超时/异常/池拒绝；成功为 null）
 * @param turnId     轮次标识（执行期检索携带；预取 fire-and-forget 为 null）
 * @param traceId    执行链标识（执行期检索携带；预取为 null）
 * @param startedAt  检索发起时刻（排序键；未采集为 null）
 */
public record KbRetrievalLog(String sessionId, String agentName, String source,
                             String query, String kbs,
                             Integer hitCount, Long durationMs, boolean ok, String errorMsg,
                             String turnId, String traceId,
                             LocalDateTime startedAt) {
}
