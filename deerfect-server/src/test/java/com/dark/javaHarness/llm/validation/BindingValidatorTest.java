package com.dark.javaHarness.llm.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.domain.entity.AgentEntity;
import com.dark.javaHarness.domain.entity.LlmModelEntity;
import com.dark.javaHarness.domain.entity.LlmProviderEntity;
import com.dark.javaHarness.llm.catalog.AgentCatalog;
import com.dark.javaHarness.llm.catalog.ProviderCatalog;
import com.dark.javaHarness.llm.routing.ModelResolver;
import com.dark.javaHarness.llm.routing.RoutePlan;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 绑定校验：工具/思考能力与凭证。 */
class BindingValidatorTest {

    private final AgentCatalog agentCatalog = mock(AgentCatalog.class);
    private final ModelResolver resolver = mock(ModelResolver.class);
    private final ProviderCatalog providerCatalog = mock(ProviderCatalog.class);
    private final BindingValidator validator =
            new BindingValidator(agentCatalog, resolver, providerCatalog);

    @Test
    void noTools_passes() {
        stubPlan("{\"tool_calling\":false,\"thinking\":false}");
        assertDoesNotThrow(() -> validator.validate(agent(null, null)));
    }

    @Test
    void toolsWithoutCapability_throws() {
        stubPlan("{\"tool_calling\":false}");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> validator.validate(agent("webTools", null)));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("不支持工具调用"));
    }

    @Test
    void thinkingOffWithoutCapability_throws() {
        stubPlan("{\"tool_calling\":true,\"thinking\":false}");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> validator.validate(agent(null, "{\"thinking_mode\":\"off\"}")));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("不支持思考开关"));
    }

    @Test
    void missingRoute_throws() {
        AgentEntity agent = agent(null, null);
        agent.setRouteId(null);
        assertThrows(IllegalStateException.class, () -> validator.validate(agent));
    }

    private void stubPlan(String capabilities) {
        RoutePlan.Member member = new RoutePlan.Member(10L, 1L, "dashscope",
                "https://example.com", "m", "M", 10, capabilities, null);
        when(resolver.resolve(anyLong())).thenReturn(new RoutePlan(1L, "main", List.of(member)));
        LlmModelEntity model = new LlmModelEntity();
        model.setId(10L);
        model.setProviderId(1L);
        when(providerCatalog.requireModel(10L)).thenReturn(model);
        LlmProviderEntity provider = new LlmProviderEntity();
        provider.setId(1L);
        provider.setCode("dashscope");
        when(providerCatalog.requireProvider(1L)).thenReturn(provider);
        when(providerCatalog.requireCredential(provider)).thenReturn("sk-test");
    }

    private static AgentEntity agent(String tools, String params) {
        AgentEntity agent = new AgentEntity();
        agent.setAgentName("general");
        agent.setRouteId(1L);
        agent.setTools(tools);
        agent.setParams(params);
        return agent;
    }
}
