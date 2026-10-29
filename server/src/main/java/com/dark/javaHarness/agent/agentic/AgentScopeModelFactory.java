package com.dark.javaHarness.agent.agentic;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dark.javaHarness.config.agent.ChatClientFactory;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.entity.ModelProviderEntity;
import com.dark.javaHarness.mapper.ModelProviderMapper;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AgentScope 模型工厂：把项目 model_provider 表的一行转成 AgentScope {@link Model}。
 *
 * <p>Key 解析复用 {@link ChatClientFactory#resolveApiKey}（yaml 显式映射 > 环境变量约定），
 * 与 ChatClient 路径保持同一口径；模型名/端点全部来自数据库，新增供应商仍零代码。
 * disableThinking=1 的端点通过 OpenAI 兼容请求体注入 {@code enable_thinking:false}
 * （与 ThinkingSwitchChatModel 同语义）。
 */
@Component
public class AgentScopeModelFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeModelFactory.class);

    private final ModelProviderMapper modelProviderMapper;
    private final ChatClientFactory chatClientFactory;

    public AgentScopeModelFactory(ModelProviderMapper modelProviderMapper,
                                  ChatClientFactory chatClientFactory) {
        this.modelProviderMapper = modelProviderMapper;
        this.chatClientFactory = chatClientFactory;
    }

    /** 解析模型行：优先按 agent 绑定的 model_provider_id，未绑定回退按模型名查启用行。 */
    public ModelProviderEntity resolveRow(AgentConfig config) {
        if (config == null) {
            return null;
        }
        if (config.modelProviderId() != null) {
            ModelProviderEntity row = modelProviderMapper.selectById(config.modelProviderId());
            if (row != null && row.getStatus() != null && row.getStatus() == 1) {
                return row;
            }
        }
        if (config.model() == null || config.model().isBlank()) {
            return null;
        }
        return modelProviderMapper.selectOne(new LambdaQueryWrapper<ModelProviderEntity>()
                .eq(ModelProviderEntity::getModel, config.model())
                .eq(ModelProviderEntity::getStatus, 1)
                .last("LIMIT 1"));
    }

    /**
     * 构建 OpenAI 兼容的 AgentScope Model；行缺失或 Key 未配置时抛 IllegalStateException
     * （调用方据此回退 ChatClient 路径）。
     */
    public Model build(AgentConfig config) {
        ModelProviderEntity row = resolveRow(config);
        if (row == null) {
            throw new IllegalStateException("未找到可用的 model_provider 行（model="
                    + (config == null ? null : config.model()) + "）");
        }
        String apiKey = chatClientFactory.resolveApiKey(row.getProvider());
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("服务商 " + row.getProvider() + " 未配置 API Key");
        }
        if (row.getApiUrl() == null || row.getApiUrl().isBlank()) {
            throw new IllegalStateException("服务商 " + row.getProvider() + " 未配置 api_url");
        }
        // 模型名口径与 ChatClient 路径一致：agent 行显式 model 优先，未配置回退 provider 行 model
        String modelName = config.model() != null && !config.model().isBlank()
                ? config.model() : row.getModel();
        GenerateOptions.Builder options = GenerateOptions.builder();
        if (row.getDisableThinking() != null && row.getDisableThinking() == 1) {
            options.additionalBodyParam("enable_thinking", false);
        }
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(row.getApiUrl())
                .modelName(modelName)
                .stream(true)
                .formatter(new OpenAIChatFormatter())
                .generateOptions(options.build())
                .build();
        log.info("[agentic] 构建 AgentScope 模型 provider={} model={} baseUrl={}",
                row.getProvider(), modelName, row.getApiUrl());
        return model;
    }

    /** 观测用模型名（与构建口径一致）。 */
    public String modelName(AgentConfig config) {
        ModelProviderEntity row = resolveRow(config);
        if (row == null) {
            return config == null ? null : config.model();
        }
        return config != null && config.model() != null && !config.model().isBlank()
                ? config.model() : row.getModel();
    }
}
