package com.dark.javaHarness.config.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dark.javaHarness.domain.entity.ModelProviderEntity;
import com.dark.javaHarness.mapper.ModelProviderMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * ChatClient 注册表（Registry 模式）：统一管理各厂商的大模型客户端。
 *
 * 职责单一：维护"部署模型 id（model_provider.id）→ ChatClient"映射，供 Agent 按
 * 绑定 id 取对应客户端。启动时从 model_provider 表加载映射；具体客户端的构建委托给
 * ChatClientFactory，本类不关心 OpenAI 协议细节。模型接入与 Agent 行为彻底解耦。
 *
 * <p>以 id 为键（而非模型名）：不同供应商可存在同名模型（如腾讯代理与官方的
 * deepseek-v4-flash），各自成行各自注册，agent 表按 model_provider_id 精确绑定。
 *
 * 新增模型/服务商 = 在 model_provider 表加一行（含 provider + api_url），无需改动代码。
 */
@Component
public class ChatClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChatClientRegistry.class);

    /** volatile：reload() 整体替换引用，进行中的调用继续用旧映射，无空窗期 */
    private volatile Map<Long, ChatClient> clients = new ConcurrentHashMap<>();
    private final ChatClient defaultClient;
    private final ChatClientFactory clientFactory;
    private final ModelProviderMapper modelProviderMapper;

    public ChatClientRegistry(ChatClient.Builder dashScopeBuilder,
                              ChatClientFactory clientFactory,
                              ModelProviderMapper modelProviderMapper) {
        this.clientFactory = clientFactory;
        this.modelProviderMapper = modelProviderMapper;
        // 默认客户端：DashScope 自动配置（表无匹配或读取失败时的兜底）
        this.defaultClient = clientFactory.defaultClient(dashScopeBuilder);
        loadFromDatabase();
    }

    /** 从 model_provider 表加载映射到全新 map，构建成功后整体替换引用（热刷新安全） */
    private void loadInto(Map<Long, ChatClient> target) {
        List<ModelProviderEntity> rows = modelProviderMapper.selectList(
                new LambdaQueryWrapper<ModelProviderEntity>()
                        .eq(ModelProviderEntity::getStatus, 1));
        if (rows == null || rows.isEmpty()) {
            log.warn("model_provider 表无启用数据（status=1），对应客户端为空，请求将回退默认客户端");
            return;
        }
        for (ModelProviderEntity row : rows) {
            ChatClient client = clientFactory.build(row.getProvider(), row.getApiUrl(),
                    row.getDisableThinking() != null && row.getDisableThinking() == 1);
            if (client != null) {
                target.put(row.getId(), client);
            }
        }
        log.info("ChatClientRegistry 已从 model_provider 表加载 {} 条部署模型映射", target.size());
    }

    private void loadFromDatabase() {
        Map<Long, ChatClient> fresh = new ConcurrentHashMap<>();
        try {
            loadInto(fresh);
        } catch (Exception e) {
            log.warn("加载 model_provider 表失败，回退默认 DashScope 客户端", e);
        }
        this.clients = fresh;
    }

    /**
     * 按部署模型 id 重建客户端（丢弃其连接池）：agent 链路共用的长超时客户端，失败后调用。
     *
     * <p>与新的 LlmClientRegistry 同义，但保留给旧装配链（单测）；按 id 而非模型名。
     *
     * @param modelProviderId 部署模型 id（model_provider.id）；null 或行不存在时跳过
     */
    public void invalidate(Long modelProviderId) {
        if (modelProviderId == null) {
            return;
        }
        ModelProviderEntity row = modelProviderMapper.selectById(modelProviderId);
        if (row == null) {
            return;
        }
        ChatClient rebuilt = clientFactory.build(row.getProvider(), row.getApiUrl(),
                row.getDisableThinking() != null && row.getDisableThinking() == 1);
        if (rebuilt != null) {
            clients.put(modelProviderId, rebuilt);
            log.info("[registry] 重建客户端并丢弃旧连接池: modelProviderId={}", modelProviderId);
        }
    }

    /** 热刷新：重新读取 model_provider 表并整体替换映射（供应商管理接口新增/修改后调用，免重启） */
    public void reload() {
        try {
            Map<Long, ChatClient> fresh = new ConcurrentHashMap<>();
            loadInto(fresh);
            this.clients = fresh;
        } catch (Exception e) {
            log.warn("热刷新 model_provider 表失败，保留原映射继续服务", e);
        }
    }

    /** 动态注册：将某个部署模型 id 绑定到指定客户端 */
    public void register(Long modelProviderId, ChatClient client) {
        if (modelProviderId != null && client != null) {
            clients.put(modelProviderId, client);
        }
    }

    /** 按部署模型 id 取客户端；未匹配时回退默认 DashScope 客户端 */
    public ChatClient get(Long modelProviderId) {
        if (modelProviderId != null) {
            ChatClient c = clients.get(modelProviderId);
            if (c != null) {
                log.debug("[registry] 命中已注册客户端: modelProviderId={}", modelProviderId);
                return c;
            }
            log.info("[registry] modelProviderId={} 未命中注册表，回退默认 DashScope 客户端", modelProviderId);
        } else {
            log.info("[registry] 未绑定部署模型，使用默认 DashScope 客户端");
        }
        return defaultClient;
    }
}
