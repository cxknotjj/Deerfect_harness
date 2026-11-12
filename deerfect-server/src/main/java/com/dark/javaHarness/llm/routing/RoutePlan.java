package com.dark.javaHarness.llm.routing;

import java.util.List;

/**
 * 路由解析结果：一条路由对应的有序可用成员。
 *
 * <p>成员按 priority 升序，已过滤禁用成员、禁用模型与禁用供应商。
 */
public record RoutePlan(Long routeId, String routeCode, List<Member> members) {

    public RoutePlan {
        members = List.copyOf(members);
    }

    /** 一个可调用的模型部署及其所属供应商。 */
    public record Member(Long modelId,
                         Long providerId,
                         String providerCode,
                         String baseUrl,
                         String upstreamName,
                         String displayName,
                         int priority,
                         String capabilities,
                         String defaultParams) {
    }
}
