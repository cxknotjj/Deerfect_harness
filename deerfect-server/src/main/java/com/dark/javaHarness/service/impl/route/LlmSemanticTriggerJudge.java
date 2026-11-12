package com.dark.javaHarness.service.impl.route;

import com.dark.javaHarness.config.ChatTimeoutProperties;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.contract.api.SemanticTriggerJudge;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 语义层触发判断实现（Slow Path）：对渠道标记为「候选」的事件做一次轻量 LLM 判断。
 *
 * <p>默认关闭（app.trigger-judge.enabled=false）：关闭时不装配，渠道候选事件一律忽略。
 * 判断失败/超时/非 JSON 一律返回 false（保持安静是安全侧），不向渠道抛出异常。
 */
@Service
@ConditionalOnProperty(name = "app.trigger-judge.enabled", havingValue = "true")
public class LlmSemanticTriggerJudge implements SemanticTriggerJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmSemanticTriggerJudge.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 判断提示词：只决定「是否值得主动介入」，不生成回复内容 */
    private static final String SYSTEM_PROMPT = """
            你是群聊/私信的介入判断器。判断一条消息是否值得机器人主动回应。
            值得回应：明确的技术问题、可复现的缺陷、需要资料或操作协助、与机器人职责直接相关。
            不值得：闲聊、情绪表达、已有他人完整回答、与机器人无关、无法验证的传闻。
            只输出一行 JSON，不要解释：{"respond":true|false,"reason":"..."}
            """;

    private static final int MAX_TOKENS = 128;

    private final com.dark.javaHarness.llm.client.LlmClientRegistry llmClientRegistry;
    private final com.dark.javaHarness.llm.catalog.AgentCatalog llmAgentCatalog;
    private final com.dark.javaHarness.llm.routing.ModelResolver llmModelResolver;
    private final ChatTimeoutProperties timeouts;

    public LlmSemanticTriggerJudge(com.dark.javaHarness.llm.client.LlmClientRegistry llmClientRegistry,
                                   com.dark.javaHarness.llm.catalog.AgentCatalog llmAgentCatalog,
                                   com.dark.javaHarness.llm.routing.ModelResolver llmModelResolver,
                                   ChatTimeoutProperties timeouts) {
        this.llmClientRegistry = llmClientRegistry;
        this.llmAgentCatalog = llmAgentCatalog;
        this.llmModelResolver = llmModelResolver;
        this.timeouts = timeouts;
    }

    @Override
    public boolean shouldRespond(String channel, String conversationKey, String message) {
        try {
            com.dark.javaHarness.llm.routing.RoutePlan plan = llmModelResolver.resolve(
                    llmAgentCatalog.require("trigger-judge").getRouteId());
            com.dark.javaHarness.llm.routing.RoutePlan.Member member = plan.members().get(0);
            ChatClient client = llmClientRegistry.getLightweight(member.modelId(),
                    timeouts == null ? null : timeouts.getJudgeReadTimeoutSeconds());
            String content = client.prompt()
                    .system(SYSTEM_PROMPT)
                    .user("渠道：" + channel + "\n会话：" + conversationKey + "\n消息：" + message)
                    .options(OpenAiChatOptions.builder()
                            .model(member.upstreamName())
                            .maxTokens(MAX_TOKENS)
                            .temperature(0.0))
                    .call()
                    .chatResponse()
                    .getResult()
                    .getOutput()
                    .getText();
            if (content == null || content.isBlank()) {
                return false;
            }
            JsonNode node = MAPPER.readTree(content);
            return node.path("respond").asBoolean(false);
        } catch (Exception e) {
            log.warn("[trigger-judge] 语义层判断失败，保持安静：{}", e.getMessage());
            return false;
        }
    }
}
