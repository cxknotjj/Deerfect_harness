package com.dark.javaHarness.agent;

import java.util.UUID;

/**
 * 单次 LLM 调用的轨迹标识（调用发起方持有部分，观测贯通值对象）：
 * turnId/traceId 沿 ChatService→AgentService→Goal 显式参数链传入，parentSpan 由编排
 * 构造方指定（根调用为 null）；spanId 原则上由调用发起点在发起前经 {@link #newSpanId()}
 * 生成后随 {@code AgentChatCaller.CallContext} 落库——编排树贯通时由编排器在 lead
 * 调用发起前预生成并经状态键下发给子任务/聚合作 parentSpan，此时经
 * {@link #withSpan} 携带，发起点沿用不再重复生成。
 *
 * <p>纯内存构造（UUID + 字段引用），观测零主链路影响。
 */
record CallTrace(String turnId, String traceId, String parentSpan, String spanId) {

    /** 无轨迹场景兜底（调用方暂无 Goal 上下文） */
    static final CallTrace NONE = new CallTrace(null, null, null, null);

    /** 生成 span_id（32 位 hex UUID）：每次调用发起前调用一次，无锁无 IO */
    static String newSpanId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 从 Goal 取轮次/调用链标识构造（路径 A 直答与 fallback 重答为根调用，parentSpan 恒 null） */
    static CallTrace fromGoal(com.dark.javaHarness.domain.Goal goal) {
        return new CallTrace(goal.turnId(), goal.traceId(), null, null);
    }

    /** 编排派生调用：同一执行链（Goal 的 turn/trace）+ 指定父调用 span（lead 的 spanId） */
    static CallTrace derived(com.dark.javaHarness.domain.Goal goal, String parentSpan) {
        return new CallTrace(goal.turnId(), goal.traceId(), parentSpan, null);
    }

    /** 携带预生成 spanId（发起点检测到非 null 时沿用，不再重复生成） */
    CallTrace withSpan(String spanId) {
        return new CallTrace(turnId, traceId, parentSpan, spanId);
    }
}
