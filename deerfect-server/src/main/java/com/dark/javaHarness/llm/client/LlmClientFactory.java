package com.dark.javaHarness.llm.client;

import com.dark.javaHarness.config.agent.ChatClientFactory;
import com.dark.javaHarness.llm.routing.RoutePlan;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 按模型部署构建 ChatClient。
 *
 * <p>复用既有 {@link ChatClientFactory} 的端点与凭证解析；模型名由调用方在请求级显式下发，
 * 因此这里不绑定模型名，只决定"连哪个端点、用哪份凭证"。
 */
@Component
public class LlmClientFactory {

    private final ChatClientFactory chatClientFactory;

    public LlmClientFactory(ChatClientFactory chatClientFactory) {
        this.chatClientFactory = chatClientFactory;
    }

    /** 按路由成员构建客户端；构建失败返回 null 由调用方报错。 */
    public ChatClient build(RoutePlan.Member member) {
        return chatClientFactory.build(member.providerCode(), member.baseUrl(), false);
    }

    /** 轻量客户端（短读超时、无默认工具），供路由判定等内部短调用使用。 */
    public ChatClient buildLightweight(RoutePlan.Member member, Integer readTimeoutSeconds) {
        return chatClientFactory.buildLightweight(member.providerCode(), member.baseUrl(), readTimeoutSeconds);
    }
}
