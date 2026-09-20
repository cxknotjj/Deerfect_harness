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
    /** 模型名 → 部署模型 id 索引（供无 agent 行的场景按名查找，如路由判断）；同名歧义取先加载行并告警 */
    private volatile Map<String, Long> idsByName = new ConcurrentHashMap<>();
    /** 模型名 → 轻量客户端（路由判定用：短读超时、无默认工具）；懒构建，reload 时清空 */
    private volatile Map<String, ChatClient> lightClients = new ConcurrentHashMap<>();
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
    private void loadInto(Map<Long, ChatClient> target, Map<String, Long> nameIndex) {
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
                String nameKey = row.getModel().toLowerCase();
                Long prev = nameIndex.putIfAbsent(nameKey, row.getId());
                if (prev != null && !prev.equals(row.getId())) {
                    log.warn("模型名 '{}' 在多个供应商下重名（id={} / {}），按名查找取先加载的 id={}",
                            row.getModel(), prev, row.getId(), prev);
                }
            }
        }
        log.info("ChatClientRegistry 已从 model_provider 表加载 {} 条部署模型映射", target.size());
    }

    private void loadFromDatabase() {
        Map<Long, ChatClient> fresh = new ConcurrentHashMap<>();
        Map<String, Long> freshNames = new ConcurrentHashMap<>();
        try {
            loadInto(fresh, freshNames);
        } catch (Exception e) {
            log.warn("加载 model_provider 表失败，回退默认 DashScope 客户端", e);
        }
        this.clients = fresh;
        this.idsByName = freshNames;
    }

    /**
     * 按模型名取轻量客户端（短读超时、无默认工具），懒构建后按模型名缓存。
     * 未命中模型行或构建失败时回退默认 DashScope 客户端（与 {@link #getByModel} 同口径）。
     */
    public ChatClient getLightweightByModel(String model, Integer readTimeoutSeconds) {
        if (model == null || model.isBlank()) {
            return defaultClient;
        }
        String key = model.toLowerCase();
        ChatClient cached = lightClients.get(key);
        if (cached != null) {
            return cached;
        }
        ModelProviderEntity row = modelProviderMapper.selectOne(
                new LambdaQueryWrapper<ModelProviderEntity>()
                        .eq(ModelProviderEntity::getModel, model)
                        .eq(ModelProviderEntity::getStatus, 1)
                        .last("LIMIT 1"));
        if (row == null) {
            log.info("[registry] 模型 '{}' 未命中注册表，轻量调用回退默认 DashScope 客户端", model);
            return defaultClient;
        }
        ChatClient built = clientFactory.buildLightweight(row.getProvider(), row.getApiUrl(), readTimeoutSeconds);
        if (built == null) {
            log.warn("[registry] 模型 '{}' 轻量客户端构建失败，回退默认 DashScope 客户端", model);
            return defaultClient;
        }
        lightClients.putIfAbsent(key, built);
        return lightClients.get(key);
    }

    /**
     * 丢弃某模型的轻量客户端缓存：下次取用时重建（连同新的连接池）。
     *
     * <p>动机：长空闲后 HTTP 客户端连接池里的 keep-alive 连接可能已被网络路径静默丢弃
     * （对端不发 RST，写进去的请求石沉大海），复用该连接的调用会一直挂到读超时。
     * JDK 17 的 jdk.httpclient.keepalive.timeout 实测不淘汰空闲 HTTP/2 连接
     * （且无 .h2 变体），故在调用失败后主动丢池，使下一次重试拿到全新连接。
     */
    public void invalidateLightweight(String model) {
        if (model == null || model.isBlank()) {
            return;
        }
        if (lightClients.remove(model.toLowerCase()) != null) {
            log.info("[registry] 丢弃轻量客户端缓存（下次调用重建连接池）: model={}", model);
        }
    }

    /**
     * 按模型名重建客户端（丢弃其连接池）：agent 链路共用的长超时客户端，失败后调用。
     *
     * <p>与 {@link #invalidateLightweight} 同因不同物：后者作用于路由判定的轻量客户端，
     * 本方法作用于 {@link #getByModel}/{@link #get} 返回的共用客户端。重建会产生一个
     * 新的 HttpClient（旧实例由 GC 回收），故仅在调用失败（疑似连接黑洞）时触发。
     *
     * @param model 模型名；null/未命中注册表时不做处理
     */
    public void invalidateByModel(String model) {
        if (model == null || model.isBlank()) {
            return;
        }
        Long id = idsByName.get(model.toLowerCase());
        if (id == null) {
            return;
        }
        ModelProviderEntity row = modelProviderMapper.selectById(id);
        if (row == null) {
            return;
        }
        ChatClient rebuilt = clientFactory.build(row.getProvider(), row.getApiUrl(),
                row.getDisableThinking() != null && row.getDisableThinking() == 1);
        if (rebuilt != null) {
            clients.put(id, rebuilt);
            log.info("[registry] 重建客户端并丢弃旧连接池: model={} id={}", model, id);
        }
    }

    /** 热刷新：重新读取 model_provider 表并整体替换映射（供应商管理接口新增/修改后调用，免重启） */
    public void reload() {
        try {
            Map<Long, ChatClient> fresh = new ConcurrentHashMap<>();
            Map<String, Long> freshNames = new ConcurrentHashMap<>();
            loadInto(fresh, freshNames);
            this.clients = fresh;
            this.idsByName = freshNames;
            // 轻量客户端随主映射一同失效：下次调用按新配置重建
            this.lightClients = new ConcurrentHashMap<>();
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
                log.info("[registry] 命中已注册客户端: modelProviderId={}", modelProviderId);
                return c;
            }
            log.info("[registry] modelProviderId={} 未命中注册表，回退默认 DashScope 客户端", modelProviderId);
        } else {
            log.info("[registry] 未绑定部署模型，使用默认 DashScope 客户端");
        }
        return defaultClient;
    }

    /**
     * 按模型名取客户端（无 agent 行的场景用，如路由判断的固定模型）。
     * 同名多供应商时取先加载行（load 时已告警歧义）；未命中回退默认。
     */
    public ChatClient getByModel(String model) {
        if (model == null || model.isBlank()) {
            return defaultClient;
        }
        Long id = idsByName.get(model.toLowerCase());
        return get(id);
    }
}
