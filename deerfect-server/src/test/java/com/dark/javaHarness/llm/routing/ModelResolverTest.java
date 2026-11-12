package com.dark.javaHarness.llm.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.domain.entity.LlmModelEntity;
import com.dark.javaHarness.domain.entity.LlmProviderEntity;
import com.dark.javaHarness.domain.entity.LlmRouteEntity;
import com.dark.javaHarness.domain.entity.LlmRouteMemberEntity;
import com.dark.javaHarness.llm.catalog.ProviderCatalog;
import com.dark.javaHarness.mapper.LlmRouteMapper;
import com.dark.javaHarness.mapper.LlmRouteMemberMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 路由解析：优先级顺序、禁用过滤、空路由、路由禁用。 */
class ModelResolverTest {

    private LlmRouteMapper routeMapper;
    private LlmRouteMemberMapper memberMapper;
    private ProviderCatalog providerCatalog;
    private ModelResolver resolver;

    @BeforeEach
    void setUp() {
        routeMapper = mock(LlmRouteMapper.class);
        memberMapper = mock(LlmRouteMemberMapper.class);
        providerCatalog = mock(ProviderCatalog.class);
        resolver = new ModelResolver(routeMapper, memberMapper, providerCatalog);
    }

    @Test
    void resolve_ordersByPriority() {
        when(routeMapper.selectById(1L)).thenReturn(route(1L, "main", 1));
        when(memberMapper.selectList(any())).thenReturn(List.of(member(2L, 20), member(1L, 10)));
        when(providerCatalog.requireModel(1L)).thenReturn(model(1L, 100L, 1));
        when(providerCatalog.requireModel(2L)).thenReturn(model(2L, 100L, 1));
        when(providerCatalog.requireProvider(100L)).thenReturn(provider(100L, "p", 1));

        RoutePlan plan = resolver.resolve(1L);

        assertEquals(List.of(1L, 2L), plan.members().stream().map(RoutePlan.Member::modelId).toList());
    }

    @Test
    void resolve_skipsDisabledModel() {
        when(routeMapper.selectById(1L)).thenReturn(route(1L, "main", 1));
        when(memberMapper.selectList(any())).thenReturn(List.of(member(1L, 10), member(2L, 20)));
        when(providerCatalog.requireModel(1L)).thenReturn(model(1L, 100L, 0));
        when(providerCatalog.requireModel(2L)).thenReturn(model(2L, 100L, 1));
        when(providerCatalog.requireProvider(100L)).thenReturn(provider(100L, "p", 1));

        RoutePlan plan = resolver.resolve(1L);

        assertEquals(List.of(2L), plan.members().stream().map(RoutePlan.Member::modelId).toList());
    }

    @Test
    void resolve_emptyMembers_throws() {
        when(routeMapper.selectById(1L)).thenReturn(route(1L, "main", 1));
        when(memberMapper.selectList(any())).thenReturn(List.of());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> resolver.resolve(1L));
        assertTrue(ex.getMessage().contains("无可用成员"));
    }

    @Test
    void resolve_disabledRoute_throws() {
        when(routeMapper.selectById(1L)).thenReturn(route(1L, "main", 0));

        assertThrows(IllegalStateException.class, () -> resolver.resolve(1L));
    }

    @Test
    void resolve_nullRoute_throws() {
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(null));
    }

    private static LlmRouteEntity route(Long id, String code, int enabled) {
        LlmRouteEntity route = new LlmRouteEntity();
        route.setId(id);
        route.setCode(code);
        route.setEnabled(enabled);
        return route;
    }

    private static LlmRouteMemberEntity member(Long modelId, int priority) {
        LlmRouteMemberEntity member = new LlmRouteMemberEntity();
        member.setRouteId(1L);
        member.setModelId(modelId);
        member.setPriority(priority);
        member.setEnabled(1);
        return member;
    }

    private static LlmModelEntity model(Long id, Long providerId, int enabled) {
        LlmModelEntity model = new LlmModelEntity();
        model.setId(id);
        model.setProviderId(providerId);
        model.setUpstreamName("up-" + id);
        model.setDisplayName("M" + id);
        model.setCapabilities("{}");
        model.setEnabled(enabled);
        return model;
    }

    private static LlmProviderEntity provider(Long id, String code, int enabled) {
        LlmProviderEntity provider = new LlmProviderEntity();
        provider.setId(id);
        provider.setCode(code);
        provider.setBaseUrl("https://example.com");
        provider.setEnabled(enabled);
        return provider;
    }
}
