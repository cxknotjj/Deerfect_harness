package com.dark.javaHarness.llm.admin;

import java.util.List;

/**
 * LLM 管理接口的请求 / 响应体（供应商、模型、路由、Agent 绑定、连通性测试）。
 *
 * <p>响应体直接暴露表字段（含禁用行），不隐藏凭证引用名——凭证值本身不落库、
 * 不出现在任何响应中。
 */
public final class LlmAdminModels {

    private LlmAdminModels() {
    }

    // ---------------- 供应商 ----------------

    public record ProviderView(Long id, String code, String baseUrl, String credentialRef,
                               String protocol, Integer enabled) {
    }

    public record ProviderUpsert(String code, String baseUrl, String credentialRef,
                                 String protocol, Integer enabled) {
    }

    // ---------------- 模型 ----------------

    public record ModelView(Long id, Long providerId, String providerCode, String upstreamName,
                            String displayName, String capabilities, String defaultParams,
                            Integer enabled) {
    }

    public record ModelUpsert(Long providerId, String upstreamName, String displayName,
                              String capabilities, String defaultParams, Integer enabled) {
    }

    // ---------------- 路由 ----------------

    public record RouteMemberView(Long modelId, String upstreamName, String providerCode,
                                  Integer priority, Integer enabled) {
    }

    public record RouteView(Long id, String code, String description, String strategy,
                            Integer enabled, List<RouteMemberView> members) {
    }

    public record RouteMemberUpsert(Long modelId, Integer priority, Integer enabled) {
    }

    public record RouteUpsert(String code, String description, String strategy, Integer enabled,
                              List<RouteMemberUpsert> members) {
    }

    // ---------------- Agent 绑定 ----------------

    public record AgentRouteUpdate(Long routeId) {
    }

    // ---------------- 连通性测试 ----------------

    /**
     * 单个路由成员的测试结果。
     *
     * @param httpStatus 上游 HTTP 状态（非 HTTP 错误为 null）
     * @param errorClass 错误分类：timeout / connection / 4xx / 5xx / other（成功为 null）
     */
    public record RouteTestResult(Long modelId, String upstreamName, boolean ok, long latencyMs,
                                  Integer httpStatus, String errorClass, String error) {
    }
}
