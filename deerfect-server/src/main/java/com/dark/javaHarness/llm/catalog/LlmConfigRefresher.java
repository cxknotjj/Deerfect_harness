package com.dark.javaHarness.llm.catalog;

import com.dark.javaHarness.domain.entity.AgentEntity;
import com.dark.javaHarness.llm.client.LlmClientRegistry;
import com.dark.javaHarness.llm.routing.ModelResolver;
import com.dark.javaHarness.llm.validation.BindingValidator;
import com.dark.javaHarness.service.AgentConfigProvider;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * LLM 配置热更新：重读目录 → 全量校验 → 通过后生效；失败回滚到更新前快照。
 *
 * <p>管理接口每次写库后调用 {@link #refresh()}。校验失败时恢复旧的内存映射并清掉
 * 路由 / 客户端缓存，保证非法配置不会进入调用链，旧配置继续服务。
 */
@Component
public class LlmConfigRefresher {

    private static final Logger log = LoggerFactory.getLogger(LlmConfigRefresher.class);

    private final AgentCatalog agentCatalog;
    private final ProviderCatalog providerCatalog;
    private final ModelResolver modelResolver;
    private final LlmClientRegistry clientRegistry;
    private final BindingValidator bindingValidator;
    /** Agent 配置缓存（模型名随路由变化，刷新后必须失效，避免滞后一个 TTL） */
    private final AgentConfigProvider agentConfigProvider;

    public LlmConfigRefresher(AgentCatalog agentCatalog,
                              ProviderCatalog providerCatalog,
                              ModelResolver modelResolver,
                              LlmClientRegistry clientRegistry,
                              BindingValidator bindingValidator,
                              AgentConfigProvider agentConfigProvider) {
        this.agentCatalog = agentCatalog;
        this.providerCatalog = providerCatalog;
        this.modelResolver = modelResolver;
        this.clientRegistry = clientRegistry;
        this.bindingValidator = bindingValidator;
        this.agentConfigProvider = agentConfigProvider;
    }

    /** 重建配置并校验；任一步失败回滚到调用前状态后抛出。 */
    public synchronized void refresh() {
        Map<String, AgentEntity> previousAgents = agentCatalog.snapshot();
        ProviderCatalog.Snapshot previousProviders = providerCatalog.snapshot();
        try {
            providerCatalog.reload();
            agentCatalog.reload();
            modelResolver.invalidate();
            bindingValidator.validateAll();
            clientRegistry.clear();
            agentConfigProvider.invalidateCache();
            log.info("[llm] 配置热更新完成");
        } catch (RuntimeException e) {
            providerCatalog.replace(previousProviders);
            agentCatalog.replace(previousAgents);
            modelResolver.invalidate();
            clientRegistry.clear();
            agentConfigProvider.invalidateCache();
            log.warn("[llm] 配置热更新失败，已保留旧配置：{}", e.getMessage());
            throw e;
        }
    }
}
