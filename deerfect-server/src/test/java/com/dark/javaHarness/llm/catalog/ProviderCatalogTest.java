package com.dark.javaHarness.llm.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.domain.entity.LlmModelEntity;
import com.dark.javaHarness.domain.entity.LlmProviderEntity;
import com.dark.javaHarness.mapper.LlmModelMapper;
import com.dark.javaHarness.mapper.LlmProviderMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

/** 供应商目录：读取、凭证解析、缺失报错。 */
class ProviderCatalogTest {

    @Test
    void requireProviderAndModel_hit() {
        ProviderCatalog catalog = catalog("DASHSCOPE_API_KEY", "sk-test");

        assertEquals("dashscope", catalog.requireProvider(1L).getCode());
        assertEquals("qwen", catalog.requireModel(10L).getUpstreamName());
    }

    @Test
    void credential_missing_throwsWithoutLeakingRefValue() {
        ProviderCatalog catalog = catalog("DASHSCOPE_API_KEY", null);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> catalog.requireCredential(catalog.requireProvider(1L)));

        assertTrue(ex.getMessage().contains("DASHSCOPE_API_KEY"));
        assertTrue(!ex.getMessage().contains("null"));
    }

    private static ProviderCatalog catalog(String ref, String value) {
        LlmProviderMapper providerMapper = mock(LlmProviderMapper.class);
        LlmModelMapper modelMapper = mock(LlmModelMapper.class);
        when(providerMapper.selectList(any())).thenReturn(List.of(provider(ref)));
        when(modelMapper.selectList(any())).thenReturn(List.of(model()));
        Environment env = mock(Environment.class);
        when(env.getProperty(ref)).thenReturn(value);
        return new ProviderCatalog(providerMapper, modelMapper, env);
    }

    private static LlmProviderEntity provider(String ref) {
        LlmProviderEntity provider = new LlmProviderEntity();
        provider.setId(1L);
        provider.setCode("dashscope");
        provider.setBaseUrl("https://example.com");
        provider.setCredentialRef(ref);
        provider.setEnabled(1);
        return provider;
    }

    private static LlmModelEntity model() {
        LlmModelEntity model = new LlmModelEntity();
        model.setId(10L);
        model.setProviderId(1L);
        model.setUpstreamName("qwen");
        model.setEnabled(1);
        return model;
    }
}
