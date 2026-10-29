package com.dark.javaHarness.domain;

import com.dark.javaHarness.domain.dto.RouteInfo;
import java.util.List;

/**
 * 路由决策的完整快照：路径 + 置信度 + 工作流形态 + 来源。
 *
 * <p>与 {@link RouteDecision} 的区别：枚举只表达执行路径，本对象额外承载判定依据，
 * 供 ChatService 做置信度策略、SSE/HTTP 透出与观测。
 */
public record RoutePlan(
        RouteDecision decision,
        double confidence,
        List<String> workstreams,
        List<String> deliverables,
        String reason,
        Source source) {

    /** 决策来源：显式指定 / 规则短路 / LLM 判定 / 兜底 / 会话绑定 / 续跑 / 运行时升级 */
    public enum Source {
        OVERRIDE,
        RULE,
        LLM,
        FALLBACK,
        BOUND_AGENT,
        RESUME,
        ESCALATED
    }

    public RoutePlan {
        // 置信度来自 LLM 输出，钳制到 0~1；其余字段由调用方保证非空
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        workstreams = List.copyOf(workstreams);
        deliverables = List.copyOf(deliverables);
    }

    public boolean graph() {
        return decision == RouteDecision.COMPLEX;
    }

    public String path() {
        return graph() ? "graph" : "agentic";
    }

    public static RoutePlan agentic(double confidence, String reason, Source source) {
        return new RoutePlan(RouteDecision.SIMPLE, confidence, List.of(), List.of(), reason, source);
    }

    public static RoutePlan graph(double confidence, List<String> workstreams, List<String> deliverables,
                                  String reason, Source source) {
        return new RoutePlan(RouteDecision.COMPLEX, confidence, workstreams, deliverables, reason, source);
    }

    /** 对外透出的路由快照。 */
    public RouteInfo toInfo() {
        return new RouteInfo(path(), confidence, workstreams, deliverables, reason, source.name());
    }
}
