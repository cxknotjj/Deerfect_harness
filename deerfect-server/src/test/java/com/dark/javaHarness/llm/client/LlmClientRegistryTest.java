package com.dark.javaHarness.llm.client;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.domain.entity.LlmModelEntity;
import com.dark.javaHarness.domain.entity.LlmProviderEntity;
import com.dark.javaHarness.llm.catalog.ProviderCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/** 客户端注册表：按 id 缓存、失效重建、缺失报错。 */
class LlmClientRegistryTest {

    @Test
    void get_cachesByModelId() {
        Fixture f = new Fixture();

        ChatClient first = f.registry.get(10L);
        ChatClient second = f.registry.get(10L);

        assertSame(first, second);
        verify(f.factory, times(1)).build(any());
    }

    @Test
    void invalidate_rebuildsOnNextGet() {
        Fixture f = new Fixture();
        f.registry.get(10L);

        f.registry.invalidate(10L);
        f.registry.get(10L);

        verify(f.factory, times(2)).build(any());
    }

    @Test
    void missingModel_throwsInsteadOfFallback() {
        Fixture f = new Fixture();
        when(f.catalog.requireModel(99L)).thenThrow(new IllegalStateException("llm_model 不存在: id=99"));

        assertThrows(IllegalStateException.class, () -> f.registry.get(99L));
    }

    @Test
    void nullModelId_throws() {
        Fixture f = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> f.registry.get(null));
    }

    private static final class Fixture {
        final LlmClientFactory factory = mock(LlmClientFactory.class);
        final ProviderCatalog catalog = mock(ProviderCatalog.class);
        final LlmClientRegistry registry;

        Fixture() {
            LlmModelEntity model = new LlmModelEntity();
            model.setId(10L);
            model.setProviderId(1L);
            model.setUpstreamName("qwen");
            model.setEnabled(1);
            LlmProviderEntity provider = new LlmProviderEntity();
            provider.setId(1L);
            provider.setCode("dashscope");
            provider.setBaseUrl("https://example.com");
            provider.setEnabled(1);
            when(catalog.requireModel(10L)).thenReturn(model);
            when(catalog.requireProvider(1L)).thenReturn(provider);
            when(factory.build(any())).thenReturn(mock(ChatClient.class));
            registry = new LlmClientRegistry(factory, catalog);
        }
    }
}
