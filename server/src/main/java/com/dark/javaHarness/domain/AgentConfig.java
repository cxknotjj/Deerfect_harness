package com.dark.javaHarness.domain;

/**
 * Agent 运行配置（部署模型 + 系统提示词 + 知识库绑定 + 思考显示开关），来自 agent 表 JOIN model_provider。
 *
 * <p>modelProviderId 指向 model_provider.id（唯一确定"哪个端点的哪个模型"），
 * 用于从 {@code ChatClientRegistry} 取客户端；model 是该端点的模型名，
 * 作为请求级 model 参数发给厂商（同一模型在不同供应商下可有不同名称）。
 * knowledge 是 agent 表 knowledge 列原文（逗号分隔 kb 标识，解析在 KnowledgeRetriever），
 * null/空白 = 未绑定知识库（不做检索）。
 * thinking 是 agent 表 thinking 列（仅控制是否显示思考内容，并不是控制模型是否思考——
 * 模型思考由 model_provider.disable_thinking 端点配置决定）：NULL/0 = 不透传前端，1 = 透传。
 * 除 knowledge/thinking 外为空表示使用默认值（yaml 配置的默认客户端与模型）。
 */
public record AgentConfig(Long modelProviderId, String model, String prompt, String knowledge,
                          Boolean thinking) {

    /** 4 参便捷构造（thinking 缺省 null = 不透传显示）：既有调用点与单测零改动 */
    public AgentConfig(Long modelProviderId, String model, String prompt, String knowledge) {
        this(modelProviderId, model, prompt, knowledge, null);
    }
}
