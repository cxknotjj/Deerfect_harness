package com.dark.javaHarness.agent.agentic;

import com.dark.javaHarness.config.agent.ChatClientFactory;
import com.dark.javaHarness.contract.model.AgentConfig;
import com.dark.javaHarness.domain.entity.AgentEntity;
import com.dark.javaHarness.domain.entity.ModelProviderEntity;
import com.dark.javaHarness.llm.catalog.AgentCatalog;
import com.dark.javaHarness.llm.routing.ModelResolver;
import com.dark.javaHarness.llm.routing.RouteAttempt;
import com.dark.javaHarness.llm.routing.RoutePlan;
import com.dark.javaHarness.mapper.ModelProviderMapper;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AgentScope 模型工厂：把项目 model_provider 表的一行转成 AgentScope {@link Model}。
 *
 * <p>Key 解析复用 {@link ChatClientFactory#resolveApiKey}（yaml 显式映射 > 环境变量约定），
 * 与 ChatClient 路径保持同一口径；模型名/端点全部来自数据库，新增供应商仍零代码。
 * disableThinking=1 的端点通过 OpenAI 兼容请求体注入 {@code enable_thinking:false}
 * （与 ThinkingSwitchChatModel 同语义）。
 */
@Component
public class AgentScopeModelFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeModelFactory.class);

    private final ModelProviderMapper modelProviderMapper;
    private final ChatClientFactory chatClientFactory;
    private final ModelResolver modelResolver;
    private final AgentCatalog agentCatalog;

    public AgentScopeModelFactory(ModelProviderMapper modelProviderMapper,
                                  ChatClientFactory chatClientFactory,
                                  ModelResolver modelResolver,
                                  AgentCatalog agentCatalog) {
        this.modelProviderMapper = modelProviderMapper;
        this.chatClientFactory = chatClientFactory;
        this.modelResolver = modelResolver;
        this.agentCatalog = agentCatalog;
    }

    /** 解析模型行：Agent → 路由 → 首选成员；不按模型名兜底。 */
    public ModelProviderEntity resolveRow(String agentName, AgentConfig config) {
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalStateException("agentName 不能为空");
        }
        AgentEntity agent = agentCatalog.require(agentName);
        RoutePlan plan = modelResolver.resolve(agent.getRouteId());
        RoutePlan.Member primary = plan.members().get(0);
        ModelProviderEntity row = modelProviderMapper.selectById(primary.modelId());
        if (row == null) {
            throw new IllegalStateException("路由 " + plan.routeCode() + " 的模型不存在: modelId="
                    + primary.modelId());
        }
        return row;
    }

    /**
     * 构建 OpenAI 兼容的 AgentScope Model；行缺失或 Key 未配置时抛 IllegalStateException
     * （调用方据此回退 ChatClient 路径）。
     */
    public Model build(String agentName, AgentConfig config) {
        ModelProviderEntity row = resolveRow(agentName, config);
        if (row == null) {
            throw new IllegalStateException("未找到可用的 model_provider 行（model="
                    + (config == null ? null : config.model()) + "）");
        }
        String apiKey = chatClientFactory.resolveApiKey(row.getProvider());
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("服务商 " + row.getProvider() + " 未配置 API Key");
        }
        if (row.getApiUrl() == null || row.getApiUrl().isBlank()) {
            throw new IllegalStateException("服务商 " + row.getProvider() + " 未配置 api_url");
        }
        // 模型名口径与 ChatClient 路径一致：agent 行显式 model 优先，未配置回退 provider 行 model
        String modelName = config.model() != null && !config.model().isBlank()
                ? config.model() : row.getModel();
        GenerateOptions.Builder options = GenerateOptions.builder();
        if (row.getDisableThinking() != null && row.getDisableThinking() == 1) {
            options.additionalBodyParam("enable_thinking", false);
        }
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(row.getApiUrl())
                .modelName(modelName)
                .stream(true)
                .formatter(new OpenAIChatFormatter())
                .generateOptions(options.build())
                .build();
        log.info("[agentic] 构建 AgentScope 模型 provider={} model={} baseUrl={}",
                row.getProvider(), modelName, row.getApiUrl());
        return model;
    }

    /** 观测用模型名（与构建口径一致）。 */
    public String modelName(String agentName, AgentConfig config) {
        ModelProviderEntity row = resolveRow(agentName, config);
        if (row == null) {
            return null;
        }
        return config != null && config.model() != null && !config.model().isBlank()
                ? config.model() : row.getModel();
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
