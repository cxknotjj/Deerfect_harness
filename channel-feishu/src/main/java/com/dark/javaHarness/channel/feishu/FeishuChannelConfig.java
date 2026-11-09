package com.dark.javaHarness.channel.feishu;

import com.dark.javaHarness.contract.spi.ChannelHealth;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 飞书渠道装配（{@code channel.feishu.enabled=true} 时生效）。
 *
 * <p><b>启动即校验凭据</b>：webhook URL 就是凭证，secret 是唯一的防冒名手段。缺 secret
 * 时如果「静默按无签名发送」，等于把群机器人暴露给任何拿到 URL 的人，而且问题会一直
 * 藏着——直到有人往群里发广告。这里宁可让应用起不来。
 *
 * <p>关闭时整链不存在：没有 {@link FeishuWebhookSender}、没有 {@link ChannelHealth}，
 * {@code MessageSenderRegistry} 的渠道列表里不会出现 {@code feishu}，配错的
 * {@code notify_channel} 会在入队校验阶段被拒绝。
 */
@Configuration
@ConditionalOnProperty(prefix = "channel.feishu", name = "enabled", havingValue = "true")
public class FeishuChannelConfig {

    @Bean
    public FeishuWebhookSender feishuWebhookSender(FeishuProperties props) {
        require(props.getWebhookUrl(), "channel.feishu.webhook-url（环境变量 FEISHU_WEBHOOK_URL）");
        require(props.getWebhookSecret(), "channel.feishu.webhook-secret（环境变量 FEISHU_WEBHOOK_SECRET）");
        if (props.getAlias() == null || props.getAlias().isBlank()) {
            throw new IllegalStateException("channel.feishu.alias 不能为空：notify_channel 用它寻址");
        }
        return new FeishuWebhookSender(props);
    }

    /** 健康状态：core 经 {@link ChannelHealth} 端口读取（编译期不可见本模块） */
    @Bean
    public ChannelHealth feishuChannelHealth(FeishuProperties props, FeishuWebhookSender sender) {
        return new FeishuHealth(props, sender);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("channel.feishu.enabled=true 但缺少 " + name
                    + "：飞书 webhook 不签名等于把群机器人公开，拒绝启动");
        }
    }
}
