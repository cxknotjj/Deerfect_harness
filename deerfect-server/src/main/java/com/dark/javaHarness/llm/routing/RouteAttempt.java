package com.dark.javaHarness.llm.routing;

/**
 * 一次调用实际命中的路由成员（观测口径）。
 *
 * <p>由调用链在真正发起请求前写入；失败切换时记录从哪个成员切来，
 * 供 llm_call_log 还原切换路径。非路由调用（如旧路径）为 null。
 */
public record RouteAttempt(Long routeId, String routeCode, Long modelId, String providerCode,
                           String upstreamName, Long fallbackFromModelId) {

    /** 路由首选成员（无切换来源）。 */
    public static RouteAttempt primary(RoutePlan plan, RoutePlan.Member member) {
        return new RouteAttempt(plan.routeId(), plan.routeCode(), member.modelId(),
                member.providerCode(), member.upstreamName(), null);
    }

    /** 路由第 index 个成员；index &gt; 0 时记录上一个成员为切换来源。 */
    public static RouteAttempt at(RoutePlan plan, int index) {
        RoutePlan.Member member = plan.members().get(index);
        Long fallbackFrom = index > 0 ? plan.members().get(index - 1).modelId() : null;
        return new RouteAttempt(plan.routeId(), plan.routeCode(), member.modelId(),
                member.providerCode(), member.upstreamName(), fallbackFrom);
    }
}
