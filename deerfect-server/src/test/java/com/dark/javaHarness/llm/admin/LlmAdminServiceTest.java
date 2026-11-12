package com.dark.javaHarness.llm.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dark.javaHarness.domain.entity.AgentEntity;
import com.dark.javaHarness.domain.entity.LlmModelEntity;
import com.dark.javaHarness.domain.entity.LlmProviderEntity;
import com.dark.javaHarness.domain.entity.LlmRouteEntity;
import com.dark.javaHarness.llm.catalog.LlmConfigRefresher;
import com.dark.javaHarness.llm.catalog.ProviderCatalog;
import com.dark.javaHarness.llm.client.LlmClientRegistry;
import com.dark.javaHarness.llm.routing.ModelResolver;
import com.dark.javaHarness.llm.routing.RoutePlan;
import com.dark.javaHarness.mapper.AgentMapper;
import com.dark.javaHarness.mapper.LlmModelMapper;
import com.dark.javaHarness.mapper.LlmProviderMapper;
import com.dark.javaHarness.mapper.LlmRouteMapper;
import com.dark.javaHarness.mapper.LlmRouteMemberMapper;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/**
 * LlmAdminService 单测（mock 持久化 + 目录 + 热更新）：
 * - 写操作入参校验：非法 URL / 凭证缺失 / 重名模型 / 空路由 / 优先级重复 / 禁用模型成员
 * - 写库成功后触发热更新；热更新失败转 400 并抛错
 * - Agent 绑定改 route_id；未知 Agent / 路由被拒绝
 * - 路由连通性测试按成员返回延迟与错误分类（ok / 4xx / 5xx / timeout）
 */
@ExtendWith(MockitoExtension.class)
class LlmAdminServiceTest {

    @Mock
    private LlmProviderMapper providerMapper;
    @Mock
    private LlmModelMapper modelMapper;
    @Mock
    private LlmRouteMapper routeMapper;
    @Mock
    private LlmRouteMemberMapper memberMapper;
    @Mock
    private AgentMapper agentMapper;
    @Mock
    private ProviderCatalog providerCatalog;
    @Mock
    private ModelResolver modelResolver;
    @Mock
    private LlmClientRegistry clientRegistry;
    @Mock
    private LlmConfigRefresher refresher;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    private LlmAdminService service;

    @BeforeEach
    void setUp() {
        service = new LlmAdminService(providerMapper, modelMapper, routeMapper, memberMapper,
                agentMapper, providerCatalog, modelResolver, clientRegistry, refresher);
    }

    private static LlmAdminModels.ProviderUpsert providerReq() {
        return new LlmAdminModels.ProviderUpsert("moonshot", "https://api.moonshot.cn/v1",
                "MOONSHOT_API_KEY", "openai", 1);
    }

    private LlmProviderEntity providerRow(Long id) {
        LlmProviderEntity p = new LlmProviderEntity();
        p.setId(id);
        p.setCode("dashscope");
        p.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode");
        p.setCredentialRef("DASHSCOPE_API_KEY");
        p.setEnabled(1);
        return p;
    }

    private LlmModelEntity modelRow(Long id, Long providerId) {
        LlmModelEntity m = new LlmModelEntity();
        m.setId(id);
        m.setProviderId(providerId);
        m.setUpstreamName("qwen3.8-27b");
        m.setEnabled(1);
        return m;
    }

    /* ---------------- 供应商 ---------------- */

    @Test
    void createProvider_invalidBaseUrl_rejectedBeforeWrite() {
        LlmAdminModels.ProviderUpsert req = new LlmAdminModels.ProviderUpsert(
                "moonshot", "api.moonshot.cn", "MOONSHOT_API_KEY", "openai", 1);

        assertThrows(IllegalArgumentException.class, () -> service.createProvider(req));

        verify(providerMapper, never()).insert(any(com.dark.javaHarness.domain.entity.LlmProviderEntity.class));
        verifyNoInteractions(refresher);
    }

    @Test
    void createProvider_credentialMissing_rejected() {
        when(providerCatalog.requireCredential(any()))
                .thenThrow(new IllegalStateException("供应商 moonshot 的凭证未配置: MOONSHOT_API_KEY"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createProvider(providerReq()));

        assertTrue(error.getMessage().contains("凭证未配置"));
        verify(providerMapper, never()).insert(any(com.dark.javaHarness.domain.entity.LlmProviderEntity.class));
        verifyNoInteractions(refresher);
    }

    @Test
    void createProvider_success_writesAndRefreshes() {
        when(providerCatalog.requireCredential(any())).thenReturn("key");

        LlmAdminModels.ProviderView view = service.createProvider(providerReq());

        assertEquals("moonshot", view.code());
        assertEquals("https://api.moonshot.cn/v1", view.baseUrl());
        assertEquals(1, view.enabled());
        verify(providerMapper).insert(any(LlmProviderEntity.class));
        verify(refresher).refresh();
    }

    @Test
    void createProvider_refreshFails_throwsIllegalArgument() {
        when(providerCatalog.requireCredential(any())).thenReturn("key");
        org.mockito.Mockito.doThrow(new IllegalStateException("路由无可用成员"))
                .when(refresher).refresh();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createProvider(providerReq()));

        assertTrue(error.getMessage().startsWith("配置校验失败"), error.getMessage());
    }

    /* ---------------- 模型 ---------------- */

    @Test
    void createModel_duplicateUpstream_rejected() {
        when(providerMapper.selectById(1L)).thenReturn(providerRow(1L));
        when(modelMapper.selectList(any())).thenReturn(List.of(modelRow(5L, 1L)));
        LlmAdminModels.ModelUpsert req = new LlmAdminModels.ModelUpsert(
                1L, "qwen3.8-27b", "重复模型", null, null, 1);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createModel(req));

        assertTrue(error.getMessage().contains("已存在模型"));
        verify(modelMapper, never()).insert(any(com.dark.javaHarness.domain.entity.LlmModelEntity.class));
        verifyNoInteractions(refresher);
    }

    @Test
    void createModel_invalidCapabilitiesJson_rejected() {
        when(providerMapper.selectById(1L)).thenReturn(providerRow(1L));
        LlmAdminModels.ModelUpsert req = new LlmAdminModels.ModelUpsert(
                1L, "qwen3.8-27b", null, "{not json", null, 1);

        assertThrows(IllegalArgumentException.class, () -> service.createModel(req));
        verify(modelMapper, never()).insert(any(com.dark.javaHarness.domain.entity.LlmModelEntity.class));
    }

    /* ---------------- 路由 ---------------- */

    @Test
    void createRoute_emptyMembers_rejected() {
        LlmAdminModels.RouteUpsert req = new LlmAdminModels.RouteUpsert(
                "backup", "备用路由", "priority", 1, List.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createRoute(req));

        assertTrue(error.getMessage().contains("至少需要一个成员"));
        verify(routeMapper, never()).insert(any(com.dark.javaHarness.domain.entity.LlmRouteEntity.class));
    }

    @Test
    void createRoute_duplicatePriority_rejected() {
        when(modelMapper.selectById(10L)).thenReturn(modelRow(10L, 1L));
        when(modelMapper.selectById(11L)).thenReturn(modelRow(11L, 1L));
        when(providerMapper.selectById(1L)).thenReturn(providerRow(1L));
        LlmAdminModels.RouteUpsert req = new LlmAdminModels.RouteUpsert(
                "backup", null, "priority", 1,
                List.of(new LlmAdminModels.RouteMemberUpsert(10L, 10, 1),
                        new LlmAdminModels.RouteMemberUpsert(11L, 10, 1)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createRoute(req));

        assertTrue(error.getMessage().contains("优先级不能重复"));
        verify(routeMapper, never()).insert(any(com.dark.javaHarness.domain.entity.LlmRouteEntity.class));
    }

    @Test
    void createRoute_enabledMemberWithDisabledModel_rejected() {
        LlmModelEntity disabled = modelRow(10L, 1L);
        disabled.setEnabled(0);
        when(modelMapper.selectById(10L)).thenReturn(disabled);
        LlmAdminModels.RouteUpsert req = new LlmAdminModels.RouteUpsert(
                "backup", null, "priority", 1,
                List.of(new LlmAdminModels.RouteMemberUpsert(10L, 10, 1)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createRoute(req));

        assertTrue(error.getMessage().contains("禁用模型"));
    }

    @Test
    void createRoute_success_insertsRouteAndMembersAndRefreshes() {
        when(modelMapper.selectById(10L)).thenReturn(modelRow(10L, 1L));
        when(providerMapper.selectById(1L)).thenReturn(providerRow(1L));
        LlmAdminModels.RouteUpsert req = new LlmAdminModels.RouteUpsert(
                "backup", "备用路由", "priority", 1,
                List.of(new LlmAdminModels.RouteMemberUpsert(10L, 10, 1)));

        LlmAdminModels.RouteView view = service.createRoute(req);

        assertEquals("backup", view.code());
        verify(routeMapper).insert(any(LlmRouteEntity.class));
        verify(memberMapper).insert(any(com.dark.javaHarness.domain.entity.LlmRouteMemberEntity.class));
        verify(refresher).refresh();
    }

    /* ---------------- Agent 绑定 ---------------- */

    @Test
    void updateAgentRoute_unknownAgent_rejected() {
        when(agentMapper.selectById(99L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> service.updateAgentRoute(99L, new LlmAdminModels.AgentRouteUpdate(1L)));

        verify(agentMapper, never()).update(any(), any());
        verifyNoInteractions(refresher);
    }

    @SuppressWarnings("unchecked")
    @Test
    void updateAgentRoute_success_updatesRouteIdAndRefreshes() {
        AgentEntity agent = new AgentEntity();
        agent.setAgentId(3L);
        agent.setAgentName("general");
        when(agentMapper.selectById(3L)).thenReturn(agent);
        when(modelResolver.resolve(7L)).thenReturn(new RoutePlan(7L, "main", List.of(
                new RoutePlan.Member(10L, 1L, "dashscope", "https://example.com", "m", "m",
                        10, "{}", null))));

        service.updateAgentRoute(3L, new LlmAdminModels.AgentRouteUpdate(7L));

        ArgumentCaptor<Wrapper<AgentEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(agentMapper).update(any(), captor.capture());
        UpdateWrapper<AgentEntity> uw = (UpdateWrapper<AgentEntity>) captor.getValue();
        assertTrue(uw.getSqlSet().contains("route_id"), uw.getSqlSet());
        verify(refresher).refresh();
    }

    /* ---------------- 连通性测试 ---------------- */

    private void stubClientSuccess() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any(org.springframework.ai.openai.OpenAiChatOptions.Builder.class)))
                .thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.chatResponse()).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("ok")))));
    }

    @Test
    void testRoute_reportsOkAndClassifiedFailures() {
        when(modelResolver.resolve(7L)).thenReturn(new RoutePlan(7L, "main", List.of(
                new RoutePlan.Member(10L, 1L, "dashscope", "https://example.com", "ok-model", "m",
                        10, "{}", null),
                new RoutePlan.Member(11L, 2L, "deepseek", "https://api.deepseek.com", "auth-fail", "m",
                        20, "{}", null),
                new RoutePlan.Member(12L, 3L, "tecent", "https://chatapi.weixin.qq.com/openai", "slow", "m",
                        30, "{}", null))));
        stubClientSuccess();
        when(clientRegistry.getLightweight(eq(10L), eq(15))).thenReturn(chatClient);
        when(clientRegistry.getLightweight(eq(11L), eq(15))).thenThrow(
                HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized",
                        HttpHeaders.EMPTY, null, null));
        when(clientRegistry.getLightweight(eq(12L), eq(15))).thenThrow(
                new RuntimeException(new SocketTimeoutException("read timed out")));

        List<LlmAdminModels.RouteTestResult> results = service.testRoute(7L);

        assertEquals(3, results.size());
        assertTrue(results.get(0).ok());
        assertEquals(null, results.get(0).errorClass());

        assertEquals("4xx", results.get(1).errorClass());
        assertEquals(401, results.get(1).httpStatus());
        assertNotNull(results.get(1).error());

        assertEquals("timeout", results.get(2).errorClass());
    }

    @Test
    void testRoute_serverError_classifiedAs5xx() {
        when(modelResolver.resolve(7L)).thenReturn(new RoutePlan(7L, "main", List.of(
                new RoutePlan.Member(11L, 2L, "deepseek", "https://api.deepseek.com", "err", "m",
                        10, "{}", null))));
        when(clientRegistry.getLightweight(eq(11L), eq(15))).thenThrow(
                HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway",
                        HttpHeaders.EMPTY, null, null));

        List<LlmAdminModels.RouteTestResult> results = service.testRoute(7L);

        assertEquals("5xx", results.get(0).errorClass());
        assertEquals(502, results.get(0).httpStatus());
    }
}
