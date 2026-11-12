package com.dark.javaHarness.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 供应商录入纯规则单测 */
class ProviderRulesTest {

    @Test
    void validateProvider_acceptsLegalInput() {
        assertDoesNotThrow(() -> ProviderRules.validateProvider(
                "dashscope", "https://dashscope.aliyuncs.com", List.of("qwen3.8-27b", "text_embedding.v4")));
    }

    @Test
    void validateProvider_rejectsMissingFields() {
        assertThrows(IllegalArgumentException.class,
                () -> ProviderRules.validateProvider(null, "https://x", List.of("m")));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderRules.validateProvider("  ", "https://x", List.of("m")));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderRules.validateProvider("p", null, List.of("m")));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderRules.validateProvider("p", "  ", List.of("m")));
    }

    @Test
    void validateProvider_rejectsEmptyOrBlankModels() {
        assertThrows(IllegalArgumentException.class,
                () -> ProviderRules.validateProvider("p", "https://x", null));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderRules.validateProvider("p", "https://x", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderRules.validateProvider("p", "https://x", Arrays.asList("m", " ")));
    }

    @Test
    void validateProvider_rejectsIllegalModelName() {
        assertThrows(IllegalArgumentException.class,
                () -> ProviderRules.validateProvider("p", "https://x", List.of("`model`")));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderRules.validateProvider("p", "https://x", List.of("model[1]")));
    }
}
