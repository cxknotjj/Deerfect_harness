package com.dark.javaHarness.llm.client;

import com.dark.javaHarness.domain.entity.LlmModelEntity;
import com.dark.javaHarness.domain.entity.LlmProviderEntity;
import com.dark.javaHarness.llm.catalog.ProviderCatalog;
import com.dark.javaHarness.llm.routing.RoutePlan;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * LLM 客户端注册表：按 model_id 缓存与失效。
 *
 * <p>缓存键与失效键一律是部署模型 id，不存在按模型名或供应商标识的入口。
 * 缓存键只有模型 id：不存在按名称或供应商标识的入口。
 */
@Component
public class LlmClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(LlmClientRegistry.class);

    private final LlmClientFactory clientFactory;
    private final ProviderCatalog providerCatalog;

    private final Map<Long, ChatClient> clients = new ConcurrentHashMap<>();
    private final Map<Long, ChatClient> lightClients = new ConcurrentHashMap<>();

    public LlmClientRegistry(LlmClientFactory clientFactory, ProviderCatalog providerCatalog) {
        this.clientFactory = clientFactory;
        this.providerCatalog = providerCatalog;
    }

    /** 按部署模型 id 取客户端；未命中时构建，构建失败直接抛错。 */
    public ChatClient get(Long modelId) {
        if (modelId == null) {
            throw new IllegalArgumentException("modelId 不能为空");
        }
        ChatClient cached = clients.get(modelId);
        if (cached != null) {
            return cached;
        }
        LlmModelEntity model = providerCatalog.requireModel(modelId);
        LlmProviderEntity provider = providerCatalog.requireProvider(model.getProviderId());
        RoutePlan.Member member = new RoutePlan.Member(
                model.getId(),
                provider.getId(),
                provider.getCode(),
                provider.getBaseUrl(),
                model.getUpstreamName(),
                model.getDisplayName(),
                0,
                model.getCapabilities(),
                model.getDefaultParams());
        ChatClient built = clientFactory.build(member);
        if (built == null) {
            throw new IllegalStateException("客户端构建失败: modelId=" + modelId);
        }
        ChatClient existing = clients.putIfAbsent(modelId, built);
        return existing == null ? built : existing;
    }

    /** 按部署模型 id 取轻量客户端（短读超时），供路由判定/摘要等内部短调用使用。 */
    public ChatClient getLightweight(Long modelId, Integer readTimeoutSeconds) {
        if (modelId == null) {
            throw new IllegalArgumentException("modelId 不能为空");
        }
        ChatClient cached = lightClients.get(modelId);
        if (cached != null) {
            return cached;
        }
        LlmModelEntity model = providerCatalog.requireModel(modelId);
        LlmProviderEntity provider = providerCatalog.requireProvider(model.getProviderId());
        RoutePlan.Member member = new RoutePlan.Member(model.getId(), provider.getId(),
                provider.getCode(), provider.getBaseUrl(), model.getUpstreamName(),
                model.getDisplayName(), 0, model.getCapabilities(), model.getDefaultParams());
        ChatClient built = clientFactory.buildLightweight(member, readTimeoutSeconds);
        if (built == null) {
            throw new IllegalStateException("轻量客户端构建失败: modelId=" + modelId);
        }
        ChatClient existing = lightClients.putIfAbsent(modelId, built);
        return existing == null ? built : existing;
    }

    /** 按部署模型 id 丢弃旧客户端；下一次 get 重建连接池。 */
    public void invalidate(Long modelId) {
        if (modelId != null) {
            clients.remove(modelId);
            lightClients.remove(modelId);
        }
    }

    /** 清空全部客户端（配置热更新后调用）。 */
    public void clear() {
        clients.clear();
        lightClients.clear();
        log.info("[llm] 客户端缓存已清空");
    }
}
