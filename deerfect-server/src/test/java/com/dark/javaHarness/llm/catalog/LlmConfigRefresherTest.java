package com.dark.javaHarness.llm.catalog;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.domain.entity.AgentEntity;
import com.dark.javaHarness.domain.entity.LlmModelEntity;
import com.dark.javaHarness.domain.entity.LlmProviderEntity;
import com.dark.javaHarness.llm.client.LlmClientRegistry;
import com.dark.javaHarness.llm.routing.ModelResolver;
import com.dark.javaHarness.llm.validation.BindingValidator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * LlmConfigRefresher 单测：
 * - 成功：重读目录 → 清路由缓存 → 全量校验 → 清客户端缓存
 * - 失败：恢复更新前快照、清缓存，异常继续向上抛（管理接口据此回滚事务）
 */
@ExtendWith(MockitoExtension.class)
class LlmConfigRefresherTest {

    @Mock
    private AgentCatalog agentCatalog;
    @Mock
    private ProviderCatalog providerCatalog;
    @Mock
    private ModelResolver modelResolver;
    @Mock
    private LlmClientRegistry clientRegistry;
    @Mock
    private BindingValidator validator;

    private LlmConfigRefresher refresher() {
        return new LlmConfigRefresher(agentCatalog, providerCatalog, modelResolver,
                clientRegistry, validator);
    }

    @Test
    void refresh_success_reloadsValidatesAndClearsCaches() {
        when(agentCatalog.snapshot()).thenReturn(Map.of());
        when(providerCatalog.snapshot()).thenReturn(
                new ProviderCatalog.Snapshot(Map.of(), Map.of()));

        refresher().refresh();

        InOrder order = inOrder(providerCatalog, agentCatalog, modelResolver, validator, clientRegistry);
        order.verify(providerCatalog).reload();
        order.verify(agentCatalog).reload();
        order.verify(modelResolver).invalidate();
        order.verify(validator).validateAll();
        order.verify(clientRegistry).clear();
        verify(agentCatalog, never()).replace(any());
    }

    @Test
    void refresh_validationFails_restoresPreviousSnapshotAndRethrows() {
        AgentEntity agent = new AgentEntity();
        agent.setAgentName("general");
        Map<String, AgentEntity> agents = Map.of("general", agent);
        LlmProviderEntity provider = new LlmProviderEntity();
        provider.setId(1L);
        LlmModelEntity model = new LlmModelEntity();
        model.setId(10L);
        ProviderCatalog.Snapshot providers = new ProviderCatalog.Snapshot(
                Map.of(1L, provider), Map.of(10L, model));
        when(agentCatalog.snapshot()).thenReturn(agents);
        when(providerCatalog.snapshot()).thenReturn(providers);
        doThrow(new IllegalStateException("路由无可用成员")).when(validator).validateAll();

        assertThrows(IllegalStateException.class, () -> refresher().refresh());

        verify(providerCatalog).replace(providers);
        verify(agentCatalog).replace(agents);
        // 恢复旧快照后必须让路由缓存与客户端缓存重新基于旧配置构建
        // （校验失败时 try 内的 clientRegistry.clear 未执行，回滚分支清一次）
        verify(modelResolver, org.mockito.Mockito.times(2)).invalidate();
        verify(clientRegistry, org.mockito.Mockito.times(1)).clear();
    }

    @Test
    void refresh_reloadThrows_restoresAndRethrows() {
        when(agentCatalog.snapshot()).thenReturn(Map.of());
        when(providerCatalog.snapshot()).thenReturn(
                new ProviderCatalog.Snapshot(Map.of(), Map.of()));
        doThrow(new IllegalStateException("db down")).when(providerCatalog).reload();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> refresher().refresh());

        assertSame("db down", error.getMessage());
        verify(agentCatalog, never()).reload();
        verify(validator, never()).validateAll();
    }
}
