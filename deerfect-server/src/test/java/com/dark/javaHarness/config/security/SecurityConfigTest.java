package com.dark.javaHarness.config.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 管理面鉴权自检：默认放行但告警，require-configured=true 时缺口令必须启动失败。 */
class SecurityConfigTest {

    @Test
    void configuredToken_passes() {
        assertDoesNotThrow(() -> SecurityConfig.validateAuthConfig(true, "t0ken", null));
        assertDoesNotThrow(() -> SecurityConfig.validateAuthConfig(true, null, "pass"));
    }

    @Test
    void emptyCredentials_defaultDoesNotFail() {
        assertDoesNotThrow(() -> SecurityConfig.validateAuthConfig(false, "", null));
        assertDoesNotThrow(() -> SecurityConfig.validateAuthConfig(false, "  ", ""));
    }

    @Test
    void emptyCredentials_requireConfiguredFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SecurityConfig.validateAuthConfig(true, "", null));
        assertTrue(e.getMessage().contains("require-configured"));
        assertTrue(e.getMessage().contains("api-token"));
    }
}
