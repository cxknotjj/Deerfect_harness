package com.dark.javaHarness.channel.feishu.ws;

import com.dark.javaHarness.channel.feishu.FeishuProperties;
import com.dark.javaHarness.channel.feishu.persistence.FeishuSessionBindingMapper;
import com.dark.javaHarness.contract.api.AgentService;
import com.dark.javaHarness.contract.api.ChatService;
import com.dark.javaHarness.contract.api.SessionService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 飞书长连接（M3b）装配：{@code channel.feishu.ws.enabled=true} 时生效。
 *
 * <p>开关与出站 webhook 独立：只收回复不需要 webhook URL，只需要自建应用的
 * App ID / Secret。缺任一直接拒绝启动——长连接的握手依赖它们，静默降级只会表现为
 * 「连上了但永远收不到消息」。
 */
@Configuration
@ConditionalOnProperty(prefix = "channel.feishu.ws", name = "enabled", havingValue = "true")
@MapperScan("com.dark.javaHarness.channel.feishu.persistence")
public class FeishuWsConfig {

    @Bean
    public FeishuEventDedup feishuEventDedup() {
        return new FeishuEventDedup();
    }

    @Bean
    public FeishuApiClient feishuApiClient(FeishuProperties props) {
        require(props.getWs().getAppId(), "channel.feishu.ws.app-id（环境变量 FEISHU_APP_ID）");
        require(props.getWs().getAppSecret(), "channel.feishu.ws.app-secret（环境变量 FEISHU_APP_SECRET）");
        return new FeishuApiClient(props);
    }

    /** 入站消息的最终处理器：调 ChatService 并回复（/agent 指令需要 AgentService 列 Agent） */
    @Bean
    public FeishuMessageHandler feishuChatHandler(ChatService chatService,
                                                  SessionService sessionService,
                                                  AgentService agentService,
                                                  FeishuSessionBindingMapper bindingMapper,
                                                  FeishuApiClient api,
                                                  FeishuProperties props) {
        return new FeishuChatHandler(chatService, sessionService, agentService, bindingMapper, api, props);
    }

    @Bean
    public FeishuInboundService feishuInboundService(FeishuProperties props,
                                                     FeishuEventDedup dedup,
                                                     FeishuMessageHandler handler) {
        return new FeishuInboundService(props, dedup, handler);
    }

    @Bean
    public FeishuWsTransport feishuWsTransport(FeishuProperties props, FeishuInboundService inbound) {
        return new SdkFeishuWsTransport(props, inbound);
    }

    @Bean
    public FeishuWsClient feishuWsClient(FeishuProperties props, FeishuWsTransport transport) {
        return new FeishuWsClient(props, transport);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("channel.feishu.ws.enabled=true 但缺少 " + name
                    + "：没有凭据就建不了长连接，拒绝启动");
        }
    }
}
