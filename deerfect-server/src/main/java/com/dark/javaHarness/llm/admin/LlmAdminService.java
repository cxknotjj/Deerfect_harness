package com.dark.javaHarness.llm.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dark.javaHarness.domain.entity.AgentEntity;
import com.dark.javaHarness.domain.entity.LlmModelEntity;
import com.dark.javaHarness.domain.entity.LlmProviderEntity;
import com.dark.javaHarness.domain.entity.LlmRouteEntity;
import com.dark.javaHarness.domain.entity.LlmRouteMemberEntity;
import com.dark.javaHarness.llm.catalog.LlmConfigRefresher;
import com.dark.javaHarness.llm.catalog.ProviderCatalog;
import com.dark.javaHarness.llm.client.LlmClientRegistry;
import com.dark.javaHarness.llm.model.ModelCapabilities;
import com.dark.javaHarness.llm.routing.ModelResolver;
import com.dark.javaHarness.llm.routing.RoutePlan;
import com.dark.javaHarness.mapper.AgentMapper;
import com.dark.javaHarness.mapper.LlmModelMapper;
import com.dark.javaHarness.mapper.LlmProviderMapper;
import com.dark.javaHarness.mapper.LlmRouteMapper;
import com.dark.javaHarness.mapper.LlmRouteMemberMapper;
import com.dark.javaHarness.observability.LlmCallRecorder;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LLM 管理面业务实现：供应商 / 模型 / 路由 / Agent 绑定的 CRUD 与路由连通性测试。
 *
 * <p>所有写操作遵循同一顺序：校验入参 → 落库 → 热更新（重读 + 全量绑定校验）。
 * 热更新失败时抛出 {@link IllegalArgumentException}，事务回滚到写前状态，
 * 内存配置由 {@link LlmConfigRefresher} 恢复旧快照——不允许"部分成功"。
 */
@Service
public class LlmAdminService {

    private static final Logger log = LoggerFactory.getLogger(LlmAdminService.class);

    /** 连通性测试的读超时（秒）：只验证端点可达，不等待长回答 */
    static final int ROUTE_TEST_TIMEOUT_SECONDS = 15;

    private final LlmProviderMapper providerMapper;
    private final LlmModelMapper modelMapper;
    private final LlmRouteMapper routeMapper;
    private final LlmRouteMemberMapper memberMapper;
    private final AgentMapper agentMapper;
    private final ProviderCatalog providerCatalog;
    private final ModelResolver modelResolver;
    private final LlmClientRegistry clientRegistry;
    private final LlmConfigRefresher refresher;

    public LlmAdminService(LlmProviderMapper providerMapper,
                           LlmModelMapper modelMapper,
                           LlmRouteMapper routeMapper,
                           LlmRouteMemberMapper memberMapper,
                           AgentMapper agentMapper,
                           ProviderCatalog providerCatalog,
                           ModelResolver modelResolver,
                           LlmClientRegistry clientRegistry,
                           LlmConfigRefresher refresher) {
        this.providerMapper = providerMapper;
        this.modelMapper = modelMapper;
        this.routeMapper = routeMapper;
        this.memberMapper = memberMapper;
        this.agentMapper = agentMapper;
        this.providerCatalog = providerCatalog;
        this.modelResolver = modelResolver;
        this.clientRegistry = clientRegistry;
        this.refresher = refresher;
    }

    // ---------------- 供应商 ----------------

    public List<LlmAdminModels.ProviderView> listProviders() {
        List<LlmProviderEntity> rows = providerMapper.selectList(null);
        List<LlmAdminModels.ProviderView> views = new ArrayList<>();
        if (rows != null) {
            for (LlmProviderEntity row : rows) {
                views.add(providerView(row));
            }
        }
        return views;
    }

    @Transactional
    public LlmAdminModels.ProviderView createProvider(LlmAdminModels.ProviderUpsert req) {
        validateProvider(req);
        LlmProviderEntity row = new LlmProviderEntity();
        row.setCode(req.code().trim());
        row.setBaseUrl(req.baseUrl().trim());
        row.setCredentialRef(req.credentialRef().trim());
        row.setProtocol(blankToDefault(req.protocol(), "openai"));
        row.setEnabled(req.enabled() == null ? 1 : req.enabled());
        LocalDateTime now = LocalDateTime.now();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        providerMapper.insert(row);
        refreshAfterWrite();
        return providerView(row);
    }

    @Transactional
    public LlmAdminModels.ProviderView updateProvider(Long id, LlmAdminModels.ProviderUpsert req) {
        LlmProviderEntity row = requireProvider(id);
        validateProvider(req);
        row.setCode(req.code().trim());
        row.setBaseUrl(req.baseUrl().trim());
        row.setCredentialRef(req.credentialRef().trim());
        if (req.protocol() != null && !req.protocol().isBlank()) {
            row.setProtocol(req.protocol().trim());
        }
        if (req.enabled() != null) {
            row.setEnabled(req.enabled());
        }
        row.setUpdatedAt(LocalDateTime.now());
        providerMapper.updateById(row);
        refreshAfterWrite();
        return providerView(row);
    }

    // ---------------- 模型 ----------------

    public List<LlmAdminModels.ModelView> listModels() {
        Map<Long, String> providerCodes = new HashMap<>();
        List<LlmProviderEntity> providers = providerMapper.selectList(null);
        if (providers != null) {
            for (LlmProviderEntity p : providers) {
                providerCodes.put(p.getId(), p.getCode());
            }
        }
        List<LlmAdminModels.ModelView> views = new ArrayList<>();
        List<LlmModelEntity> rows = modelMapper.selectList(null);
        if (rows != null) {
            for (LlmModelEntity row : rows) {
                views.add(modelView(row, providerCodes.get(row.getProviderId())));
            }
        }
        return views;
    }

    @Transactional
    public LlmAdminModels.ModelView createModel(LlmAdminModels.ModelUpsert req) {
        validateModel(req, null);
        LlmModelEntity row = new LlmModelEntity();
        row.setProviderId(req.providerId());
        row.setUpstreamName(req.upstreamName().trim());
        row.setDisplayName(blankToNull(req.displayName()));
        row.setCapabilities(blankToNull(req.capabilities()));
        row.setDefaultParams(blankToNull(req.defaultParams()));
        row.setEnabled(req.enabled() == null ? 1 : req.enabled());
        LocalDateTime now = LocalDateTime.now();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        modelMapper.insert(row);
        refreshAfterWrite();
        return modelView(row, requireProvider(row.getProviderId()).getCode());
    }

    @Transactional
    public LlmAdminModels.ModelView updateModel(Long id, LlmAdminModels.ModelUpsert req) {
        LlmModelEntity row = requireModel(id);
        validateModel(req, id);
        row.setProviderId(req.providerId());
        row.setUpstreamName(req.upstreamName().trim());
        row.setDisplayName(blankToNull(req.displayName()));
        row.setCapabilities(blankToNull(req.capabilities()));
        row.setDefaultParams(blankToNull(req.defaultParams()));
        if (req.enabled() != null) {
            row.setEnabled(req.enabled());
        }
        row.setUpdatedAt(LocalDateTime.now());
        modelMapper.updateById(row);
        refreshAfterWrite();
        return modelView(row, requireProvider(row.getProviderId()).getCode());
    }

    // ---------------- 路由 ----------------

    public List<LlmAdminModels.RouteView> listRoutes() {
        Map<Long, LlmModelEntity> models = new HashMap<>();
        List<LlmModelEntity> modelRows = modelMapper.selectList(null);
        if (modelRows != null) {
            for (LlmModelEntity m : modelRows) {
                models.put(m.getId(), m);
            }
        }
        Map<Long, String> providerCodes = new HashMap<>();
        List<LlmProviderEntity> providerRows = providerMapper.selectList(null);
        if (providerRows != null) {
            for (LlmProviderEntity p : providerRows) {
                providerCodes.put(p.getId(), p.getCode());
            }
        }
        List<LlmAdminModels.RouteView> views = new ArrayList<>();
        List<LlmRouteEntity> routes = routeMapper.selectList(null);
        if (routes != null) {
            for (LlmRouteEntity route : routes) {
                views.add(routeView(route, models, providerCodes));
            }
        }
        return views;
    }

    @Transactional
    public LlmAdminModels.RouteView createRoute(LlmAdminModels.RouteUpsert req) {
        validateRoute(req, null);
        LlmRouteEntity row = new LlmRouteEntity();
        row.setCode(req.code().trim());
        row.setDescription(blankToNull(req.description()));
        row.setStrategy(blankToDefault(req.strategy(), "priority"));
        row.setEnabled(req.enabled() == null ? 1 : req.enabled());
        LocalDateTime now = LocalDateTime.now();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        routeMapper.insert(row);
        insertMembers(row.getId(), req.members());
        refreshAfterWrite();
        return routeView(row, modelMap(), providerCodeMap());
    }

    @Transactional
    public LlmAdminModels.RouteView updateRoute(Long id, LlmAdminModels.RouteUpsert req) {
        LlmRouteEntity row = requireRoute(id);
        validateRoute(req, id);
        row.setCode(req.code().trim());
        row.setDescription(blankToNull(req.description()));
        if (req.strategy() != null && !req.strategy().isBlank()) {
            row.setStrategy(req.strategy().trim());
        }
        if (req.enabled() != null) {
            row.setEnabled(req.enabled());
        }
        row.setUpdatedAt(LocalDateTime.now());
        routeMapper.updateById(row);
        memberMapper.delete(new QueryWrapper<LlmRouteMemberEntity>().eq("route_id", id));
        insertMembers(id, req.members());
        refreshAfterWrite();
        return routeView(row, modelMap(), providerCodeMap());
    }

    // ---------------- Agent 绑定 ----------------

    @Transactional
    public void updateAgentRoute(Long agentId, LlmAdminModels.AgentRouteUpdate req) {
        if (req == null || req.routeId() == null) {
            throw new IllegalArgumentException("routeId 不能为空");
        }
        AgentEntity agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new IllegalArgumentException("Agent 不存在: id=" + agentId);
        }
        modelResolver.invalidate();
        RoutePlan plan = modelResolver.resolve(req.routeId());
        agentMapper.update(null, new UpdateWrapper<AgentEntity>()
                .eq("agent_id", agentId)
                .set("route_id", plan.routeId())
                .set("updated_at", LocalDateTime.now()));
        refreshAfterWrite();
    }

    // ---------------- 路由连通性测试（F3） ----------------

    /**
     * 按优先级逐个成员发一条最小请求，返回每个成员的延迟与错误分类。
     * 直接走轻量客户端，不落会话与业务表、不写 llm_call_log。
     */
    public List<LlmAdminModels.RouteTestResult> testRoute(Long routeId) {
        RoutePlan plan = modelResolver.resolve(routeId);
        List<LlmAdminModels.RouteTestResult> results = new ArrayList<>();
        for (RoutePlan.Member member : plan.members()) {
            results.add(testMember(member));
        }
        return results;
    }

    private LlmAdminModels.RouteTestResult testMember(RoutePlan.Member member) {
        long start = System.currentTimeMillis();
        try {
            ChatClient client = clientRegistry.getLightweight(member.modelId(), ROUTE_TEST_TIMEOUT_SECONDS);
            String text = client.prompt()
                    .system("你是连通性探针：收到任何输入都只回复 ok。")
                    .user("ping")
                    .options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                            .model(member.upstreamName())
                            .maxTokens(8)
                            .temperature(0.0))
                    .call()
                    .chatResponse()
                    .getResult()
                    .getOutput()
                    .getText();
            boolean ok = text != null && !text.isBlank();
            return new LlmAdminModels.RouteTestResult(member.modelId(), member.upstreamName(), ok,
                    System.currentTimeMillis() - start, null, ok ? null : "empty",
                    ok ? null : "模型返回空内容");
        } catch (RuntimeException e) {
            log.warn("[llm-admin] 路由成员连通性测试失败: modelId={} ({}) {}",
                    member.modelId(), member.upstreamName(), LlmCallRecorder.describeError(e));
            return new LlmAdminModels.RouteTestResult(member.modelId(), member.upstreamName(), false,
                    System.currentTimeMillis() - start, httpStatus(e), errorClass(e),
                    truncate(LlmCallRecorder.describeError(e)));
        }
    }

    private static String errorClass(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof org.springframework.web.client.HttpStatusCodeException hse) {
                int code = hse.getStatusCode().value();
                return code >= 500 ? "5xx" : code >= 400 ? "4xx" : "other";
            }
            if (t instanceof java.net.SocketTimeoutException
                    || t instanceof java.util.concurrent.TimeoutException) {
                return "timeout";
            }
            if (t instanceof java.io.IOException) {
                return "connection";
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return "other";
    }

    private static Integer httpStatus(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof org.springframework.web.client.HttpStatusCodeException hse) {
                return hse.getStatusCode().value();
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    // ---------------- 校验 ----------------

    private void validateProvider(LlmAdminModels.ProviderUpsert req) {
        if (req == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        requireText(req.code(), "code");
        requireText(req.baseUrl(), "baseUrl");
        requireText(req.credentialRef(), "credentialRef");
        if (!req.baseUrl().startsWith("http://") && !req.baseUrl().startsWith("https://")) {
            throw new IllegalArgumentException("baseUrl 必须是 http(s) 地址");
        }
        // 凭证必须能解析：写库前暴露配置错误（异常文本不含密钥值）
        LlmProviderEntity probe = new LlmProviderEntity();
        probe.setCode(req.code().trim());
        probe.setCredentialRef(req.credentialRef().trim());
        try {
            providerCatalog.requireCredential(probe);
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    private void validateModel(LlmAdminModels.ModelUpsert req, Long selfId) {
        if (req == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (req.providerId() == null) {
            throw new IllegalArgumentException("providerId 不能为空");
        }
        requireProvider(req.providerId());
        requireText(req.upstreamName(), "upstreamName");
        if (req.capabilities() != null && !req.capabilities().isBlank()) {
            ModelCapabilities.parse(req.capabilities());
        }
        if (req.defaultParams() != null && !req.defaultParams().isBlank()) {
            com.dark.javaHarness.llm.call.LlmCallOptions.merge(null, req.defaultParams(), null);
        }
        QueryWrapper<LlmModelEntity> same = new QueryWrapper<LlmModelEntity>()
                .eq("provider_id", req.providerId())
                .eq("upstream_name", req.upstreamName().trim());
        List<LlmModelEntity> existing = modelMapper.selectList(same);
        if (existing != null) {
            for (LlmModelEntity row : existing) {
                if (selfId == null || !selfId.equals(row.getId())) {
                    throw new IllegalArgumentException("同一供应商下已存在模型 " + req.upstreamName().trim());
                }
            }
        }
    }

    private void validateRoute(LlmAdminModels.RouteUpsert req, Long selfId) {
        if (req == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        requireText(req.code(), "code");
        List<LlmAdminModels.RouteMemberUpsert> members = req.members();
        if (members == null || members.isEmpty()) {
            throw new IllegalArgumentException("路由至少需要一个成员");
        }
        Set<Integer> priorities = new HashSet<>();
        Set<Long> modelIds = new HashSet<>();
        for (LlmAdminModels.RouteMemberUpsert member : members) {
            if (member == null || member.modelId() == null) {
                throw new IllegalArgumentException("成员 modelId 不能为空");
            }
            LlmModelEntity model = requireModel(member.modelId());
            if (!modelIds.add(member.modelId())) {
                throw new IllegalArgumentException("成员模型重复: " + member.modelId());
            }
            boolean memberEnabled = member.enabled() == null || member.enabled() == 1;
            if (memberEnabled) {
                if (model.getEnabled() == null || model.getEnabled() != 1) {
                    throw new IllegalArgumentException("启用的成员不能引用禁用模型: " + member.modelId());
                }
                LlmProviderEntity provider = requireProvider(model.getProviderId());
                if (provider.getEnabled() == null || provider.getEnabled() != 1) {
                    throw new IllegalArgumentException("启用的成员不能引用禁用供应商: " + provider.getCode());
                }
            }
            int priority = member.priority() == null ? 0 : member.priority();
            if (priority <= 0) {
                throw new IllegalArgumentException("成员优先级必须为正整数");
            }
            if (!priorities.add(priority)) {
                throw new IllegalArgumentException("同一路由内优先级不能重复: " + priority);
            }
        }
        List<LlmRouteEntity> sameCode = routeMapper.selectList(
                new QueryWrapper<LlmRouteEntity>().eq("code", req.code().trim()));
        if (sameCode != null) {
            for (LlmRouteEntity row : sameCode) {
                if (selfId == null || !selfId.equals(row.getId())) {
                    throw new IllegalArgumentException("路由代码已存在: " + req.code().trim());
                }
            }
        }
    }

    private void insertMembers(Long routeId, List<LlmAdminModels.RouteMemberUpsert> members) {
        for (LlmAdminModels.RouteMemberUpsert source : members) {
            LlmRouteMemberEntity row = new LlmRouteMemberEntity();
            row.setRouteId(routeId);
            row.setModelId(source.modelId());
            row.setPriority(source.priority());
            row.setEnabled(source.enabled() == null ? 1 : source.enabled());
            memberMapper.insert(row);
        }
    }

    /** 写库后触发热更新；校验失败转 400（事务随 RuntimeException 回滚） */
    private void refreshAfterWrite() {
        try {
            refresher.refresh();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("配置校验失败：" + e.getMessage(), e);
        }
    }

    // ---------------- 查询辅助 ----------------

    private LlmProviderEntity requireProvider(Long id) {
        LlmProviderEntity row = id == null ? null : providerMapper.selectById(id);
        if (row == null) {
            throw new IllegalArgumentException("供应商不存在: id=" + id);
        }
        return row;
    }

    private LlmModelEntity requireModel(Long id) {
        LlmModelEntity row = id == null ? null : modelMapper.selectById(id);
        if (row == null) {
            throw new IllegalArgumentException("模型不存在: id=" + id);
        }
        return row;
    }

    private LlmRouteEntity requireRoute(Long id) {
        LlmRouteEntity row = id == null ? null : routeMapper.selectById(id);
        if (row == null) {
            throw new IllegalArgumentException("路由不存在: id=" + id);
        }
        return row;
    }

    private Map<Long, LlmModelEntity> modelMap() {
        Map<Long, LlmModelEntity> models = new HashMap<>();
        List<LlmModelEntity> rows = modelMapper.selectList(null);
        if (rows != null) {
            for (LlmModelEntity row : rows) {
                models.put(row.getId(), row);
            }
        }
        return models;
    }

    private Map<Long, String> providerCodeMap() {
        Map<Long, String> codes = new HashMap<>();
        List<LlmProviderEntity> rows = providerMapper.selectList(null);
        if (rows != null) {
            for (LlmProviderEntity row : rows) {
                codes.put(row.getId(), row.getCode());
            }
        }
        return codes;
    }

    private LlmAdminModels.RouteView routeView(LlmRouteEntity route,
                                               Map<Long, LlmModelEntity> models,
                                               Map<Long, String> providerCodes) {
        List<LlmRouteMemberEntity> rows = memberMapper.selectList(
                new QueryWrapper<LlmRouteMemberEntity>().eq("route_id", route.getId()));
        List<LlmAdminModels.RouteMemberView> members = new ArrayList<>();
        if (rows != null) {
            rows.sort(Comparator.comparingInt(
                    r -> r.getPriority() == null ? Integer.MAX_VALUE : r.getPriority()));
            for (LlmRouteMemberEntity row : rows) {
                LlmModelEntity model = models.get(row.getModelId());
                String providerCode = model == null ? null : providerCodes.get(model.getProviderId());
                members.add(new LlmAdminModels.RouteMemberView(row.getModelId(),
                        model == null ? null : model.getUpstreamName(), providerCode,
                        row.getPriority(), row.getEnabled()));
            }
        }
        return new LlmAdminModels.RouteView(route.getId(), route.getCode(), route.getDescription(),
                route.getStrategy(), route.getEnabled(), members);
    }

    private static LlmAdminModels.ProviderView providerView(LlmProviderEntity row) {
        return new LlmAdminModels.ProviderView(row.getId(), row.getCode(), row.getBaseUrl(),
                row.getCredentialRef(), row.getProtocol(), row.getEnabled());
    }

    private static LlmAdminModels.ModelView modelView(LlmModelEntity row, String providerCode) {
        return new LlmAdminModels.ModelView(row.getId(), row.getProviderId(), providerCode,
                row.getUpstreamName(), row.getDisplayName(), row.getCapabilities(),
                row.getDefaultParams(), row.getEnabled());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
