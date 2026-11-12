package com.dark.javaHarness.llm.routing;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dark.javaHarness.domain.entity.LlmModelEntity;
import com.dark.javaHarness.domain.entity.LlmProviderEntity;
import com.dark.javaHarness.domain.entity.LlmRouteEntity;
import com.dark.javaHarness.domain.entity.LlmRouteMemberEntity;
import com.dark.javaHarness.llm.catalog.ProviderCatalog;
import com.dark.javaHarness.mapper.LlmRouteMapper;
import com.dark.javaHarness.mapper.LlmRouteMemberMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 路由解析器：route_id → 有序可用成员。
 *
 * <p>唯一的路由解析实现，不提供任何按模型名查找的入口。解析结果按 route_id 缓存，
 * 配置热更新时整体清空。
 */
@Component
public class ModelResolver {

    private static final Logger log = LoggerFactory.getLogger(ModelResolver.class);

    private final LlmRouteMapper routeMapper;
    private final LlmRouteMemberMapper memberMapper;
    private final ProviderCatalog providerCatalog;

    private final Map<Long, RoutePlan> cache = new ConcurrentHashMap<>();

    public ModelResolver(LlmRouteMapper routeMapper,
                         LlmRouteMemberMapper memberMapper,
                         ProviderCatalog providerCatalog) {
        this.routeMapper = routeMapper;
        this.memberMapper = memberMapper;
        this.providerCatalog = providerCatalog;
    }

    /**
     * 解析路由；路由不存在、禁用、成员为空、成员引用的模型或供应商禁用均抛错。
     */
    public RoutePlan resolve(Long routeId) {
        if (routeId == null) {
            throw new IllegalArgumentException("routeId 不能为空");
        }
        RoutePlan cached = cache.get(routeId);
        if (cached != null) {
            return cached;
        }
        RoutePlan plan = doResolve(routeId);
        cache.put(routeId, plan);
        return plan;
    }

    /** 清空解析缓存（配置热更新后调用）。 */
    public void invalidate() {
        cache.clear();
        log.info("[llm] 路由解析缓存已清空");
    }

    private RoutePlan doResolve(Long routeId) {
        LlmRouteEntity route = routeMapper.selectById(routeId);
        if (route == null || route.getEnabled() == null || route.getEnabled() != 1) {
            throw new IllegalStateException("llm_route 不存在或未启用: id=" + routeId);
        }
        List<LlmRouteMemberEntity> rows = memberMapper.selectList(
                new QueryWrapper<LlmRouteMemberEntity>()
                        .eq("route_id", routeId)
                        .eq("enabled", 1)
                        .orderByAsc("priority"));
        if (rows != null) {
            // 顺序是路由语义的一部分：不依赖 SQL 排序结果，解析器自己保证
            rows = rows.stream()
                    .sorted(java.util.Comparator.comparingInt(
                            r -> r.getPriority() == null ? Integer.MAX_VALUE : r.getPriority()))
                    .toList();
        }
        List<RoutePlan.Member> members = new ArrayList<>();
        if (rows != null) {
            for (LlmRouteMemberEntity row : rows) {
                LlmModelEntity model = providerCatalog.requireModel(row.getModelId());
                if (model.getEnabled() == null || model.getEnabled() != 1) {
                    log.warn("[llm] 路由 {} 的成员模型 {} 已禁用，跳过", route.getCode(), model.getId());
                    continue;
                }
                LlmProviderEntity provider = providerCatalog.requireProvider(model.getProviderId());
                if (provider.getEnabled() == null || provider.getEnabled() != 1) {
                    log.warn("[llm] 路由 {} 的成员供应商 {} 已禁用，跳过", route.getCode(), provider.getCode());
                    continue;
                }
                members.add(new RoutePlan.Member(
                        model.getId(),
                        provider.getId(),
                        provider.getCode(),
                        provider.getBaseUrl(),
                        model.getUpstreamName(),
                        model.getDisplayName(),
                        row.getPriority() == null ? Integer.MAX_VALUE : row.getPriority(),
                        model.getCapabilities(),
                        model.getDefaultParams()));
            }
        }
        if (members.isEmpty()) {
            throw new IllegalStateException("路由无可用成员: route=" + route.getCode()
                    + "（检查成员、模型与供应商的启用状态）");
        }
        return new RoutePlan(route.getId(), route.getCode(), members);
    }
}
