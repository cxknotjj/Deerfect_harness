package com.dark.javaHarness.llm.validation;

import com.dark.javaHarness.domain.entity.AgentEntity;
import com.dark.javaHarness.domain.entity.LlmModelEntity;
import com.dark.javaHarness.domain.entity.LlmProviderEntity;
import com.dark.javaHarness.llm.call.LlmCallOptions;
import com.dark.javaHarness.llm.catalog.AgentCatalog;
import com.dark.javaHarness.llm.catalog.ProviderCatalog;
import com.dark.javaHarness.llm.model.ModelCapabilities;
import com.dark.javaHarness.llm.routing.ModelResolver;
import com.dark.javaHarness.llm.routing.RoutePlan;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 绑定校验：启动与热加载时确认每个 Agent 的路由、能力与凭证可用。
 *
 * <p>校验失败直接抛错，不静默回退默认客户端。
 */
@Component
public class BindingValidator {

    private static final Logger log = LoggerFactory.getLogger(BindingValidator.class);

    private final AgentCatalog agentCatalog;
    private final ModelResolver modelResolver;
    private final ProviderCatalog providerCatalog;

    public BindingValidator(AgentCatalog agentCatalog,
                            ModelResolver modelResolver,
                            ProviderCatalog providerCatalog) {
        this.agentCatalog = agentCatalog;
        this.modelResolver = modelResolver;
        this.providerCatalog = providerCatalog;
    }

    @PostConstruct
    public void validateOnStartup() {
        validateAll();
        log.info("[llm] 绑定校验通过");
    }

    /** 校验全部启用 Agent；错误聚合后一次抛出。 */
    public void validateAll() {
        List<String> errors = new ArrayList<>();
        for (AgentEntity agent : agentCatalog.all()) {
            try {
                validate(agent);
            } catch (RuntimeException e) {
                errors.add(agent.getAgentName() + ": " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("LLM 绑定校验失败: " + String.join("; ", errors));
        }
    }

    /** 校验单个 Agent。 */
    public void validate(AgentEntity agent) {
        if (agent.getRouteId() == null) {
            throw new IllegalStateException("未绑定 route_id");
        }
        RoutePlan plan = modelResolver.resolve(agent.getRouteId());
        boolean needsTools = agent.getTools() != null && !agent.getTools().isBlank();
        LlmCallOptions options = LlmCallOptions.merge(null, null, agent.getParams());
        for (RoutePlan.Member member : plan.members()) {
            ModelCapabilities caps = ModelCapabilities.parse(member.capabilities());
            if (needsTools && !caps.toolCalling()) {
                throw new IllegalStateException("路由 " + plan.routeCode() + " 的成员 "
                        + member.modelId() + " 不支持工具调用，但 Agent 配置了工具");
            }
            if (options.thinkingMode() == LlmCallOptions.ThinkingMode.OFF && !caps.thinking()) {
                throw new IllegalStateException("路由 " + plan.routeCode() + " 的成员 "
                        + member.modelId() + " 不支持思考开关，但 Agent 要求关闭思考");
            }
            if (caps.contextWindow() > 0 && options.maxTokens() != null
                    && options.maxTokens() > caps.contextWindow()) {
                throw new IllegalStateException("Agent max_tokens=" + options.maxTokens()
                        + " 超过成员 " + member.modelId() + " 的上下文窗口 " + caps.contextWindow());
            }
            LlmModelEntity model = providerCatalog.requireModel(member.modelId());
            LlmProviderEntity provider = providerCatalog.requireProvider(model.getProviderId());
            providerCatalog.requireCredential(provider);
        }
    }
}
