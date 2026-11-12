package com.dark.javaHarness.contract.model;

/**
 * Agent 运行配置（首选部署模型 + 系统提示词 + 知识库绑定 + 思考显示开关），来自
 * agent 表与其绑定的路由首选成员。
 *
 * <p>modelId 指向语义上的部署模型 id（llm_model.id，路由首选成员的 model id），
 * model 是该部署的 upstream_name，作为请求级 model 参数发给厂商。
 * knowledge 是 agent 表 knowledge 列原文（逗号分隔 kb 标识，解析在 KnowledgeRetriever），
 * null/空白 = 未绑定知识库（不做检索）。
 * thinking 是 agent 表 thinking 列（思考显示开关）：仅控制该 agent 的思考内容是否透传前端显示，
 * 与模型思考功能开关（enable_thinking，模型域）无关；NULL/0 = 不透传，1 = 透传。
 * 除 knowledge/thinking 外为空表示使用默认值。
 */
public record AgentConfig(Long modelId, String model, String prompt, String knowledge,
                          Boolean thinking) {

    /** 4 参便捷构造（thinking 缺省 null = 不透传显示） */
    public AgentConfig(Long modelId, String model, String prompt, String knowledge) {
        this(modelId, model, prompt, knowledge, null);
    }
}
