package com.dark.javaHarness.agent.agentic;

import com.dark.javaHarness.config.agent.ChatClientFactory;
import com.dark.javaHarness.contract.model.AgentConfig;
import com.dark.javaHarness.domain.entity.AgentEntity;
import com.dark.javaHarness.domain.entity.LlmModelEntity;
import com.dark.javaHarness.domain.entity.LlmProviderEntity;
import com.dark.javaHarness.llm.call.LlmCallOptions;
import com.dark.javaHarness.llm.catalog.AgentCatalog;
import com.dark.javaHarness.llm.catalog.ProviderCatalog;
import com.dark.javaHarness.llm.routing.ModelResolver;
import com.dark.javaHarness.llm.routing.RouteAttempt;
import com.dark.javaHarness.llm.routing.RoutePlan;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AgentScope 模型工厂：把 Agent 绑定的路由首选成员转成 AgentScope {@link Model}。
 *
 * <p>Key 解析复用 {@link ChatClientFactory#resolveApiKey}（yaml 显式映射 > 环境变量约定），
 * 与 ChatClient 路径保持同一口径；模型名/端点全部来自 llm_provider / llm_model。
 * 默认参数 {@code thinking_mode=off} 时通过 OpenAI 兼容请求体注入 {@code enable_thinking:false}
 * （与 ThinkingSwitchChatModel 同语义）。
 */
@Component
public class AgentScopeModelFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeModelFactory.class);

    private final ChatClientFactory chatClientFactory;
    private final ModelResolver modelResolver;
    private final AgentCatalog agentCatalog;
    private final ProviderCatalog providerCatalog;

    public AgentScopeModelFactory(ChatClientFactory chatClientFactory,
                                  ModelResolver modelResolver,
                                  AgentCatalog agentCatalog,
                                  ProviderCatalog providerCatalog) {
        this.chatClientFactory = chatClientFactory;
        this.modelResolver = modelResolver;
        this.agentCatalog = agentCatalog;
        this.providerCatalog = providerCatalog;
    }

    /** 解析路由首选成员的模型行：Agent → 路由 → 首选成员；不按模型名兜底。 */
    public LlmModelEntity resolveModel(String agentName) {
        AgentEntity agent = agentCatalog.require(agentName);
        RoutePlan plan = modelResolver.resolve(agent.getRouteId());
        return providerCatalog.requireModel(plan.members().get(0).modelId());
    }

    /**
     * 构建 OpenAI 兼容的 AgentScope Model；配置缺失或 Key 未配置时抛 IllegalStateException
     * （调用方据此回退 ChatClient 路径）。
     */
    public Model build(String agentName, AgentConfig config) {
        LlmModelEntity model = resolveModel(agentName);
        LlmProviderEntity provider = providerCatalog.requireProvider(model.getProviderId());
        String apiKey = chatClientFactory.resolveApiKey(provider.getCode());
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("服务商 " + provider.getCode() + " 未配置 API Key");
        }
        if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
            throw new IllegalStateException("服务商 " + provider.getCode() + " 未配置 base_url");
        }
        String modelName = modelName(model, config);
        GenerateOptions.Builder options = GenerateOptions.builder();
        if (LlmCallOptions.merge(modelName, model.getDefaultParams(), null).thinkingMode()
                == LlmCallOptions.ThinkingMode.OFF) {
            options.additionalBodyParam("enable_thinking", false);
        }
        OpenAIChatModel built = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(provider.getBaseUrl())
                .modelName(modelName)
                .stream(true)
                .formatter(new OpenAIChatFormatter())
                .generateOptions(options.build())
                .build();
        log.info("[agentic] 构建 AgentScope 模型 provider={} model={} baseUrl={}",
                provider.getCode(), modelName, provider.getBaseUrl());
        return built;
    }

    /** 观测用模型名（与构建口径一致）。 */
    public String modelName(String agentName, AgentConfig config) {
        return modelName(resolveModel(agentName), config);
    }

    private static String modelName(LlmModelEntity model, AgentConfig config) {
        if (config != null && config.model() != null && !config.model().isBlank()) {
            return config.model();
        }
        return model.getUpstreamName();
    }

    /**
     * 观测用路由成员（Agent → 路由 → 首选成员）；解析失败返回 null。
     * 观测是纯旁路：失败不阻断调用，真正的配置错误由 {@link #build} 抛出。
     */
    public RouteAttempt routeAttempt(String agentName, AgentConfig config) {
        try {
            AgentEntity agent = agentCatalog.require(agentName);
            RoutePlan plan = modelResolver.resolve(agent.getRouteId());
            return RouteAttempt.primary(plan, plan.members().get(0));
        } catch (RuntimeException e) {
            log.debug("[agentic] 路由观测解析失败（忽略）：{}", e.getMessage());
            return null;
        }
    }
}
