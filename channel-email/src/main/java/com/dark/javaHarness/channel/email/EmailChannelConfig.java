package com.dark.javaHarness.channel.email;

import com.dark.javaHarness.contract.spi.ChannelHealth;
import com.dark.javaHarness.contract.spi.MessageSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * 邮件渠道装配（{@code channel.email.enabled=true} 时生效）。
 *
 * <p>显式 new {@link JavaMailSenderImpl} 而不是依赖 {@code MailSenderAutoConfiguration}：
 * 后者的触发条件是 {@code spring.mail.host}，与本模块的配置前缀无关，容易变成「配了
 * channel.email 却没生效」或「没配 channel.email 却冒出一个 Bean」。这里自己装配，
 * 「哪个 Bean 从哪来」一眼可见。
 *
 * <p>关闭时整链不存在：没有 JavaMailSender、没有 MessageSender、也没有 ChannelHealth——
 * {@code MessageSenderRegistry} 的渠道列表里不会出现 email，配错的 notify_channel 会在
 * 入队校验阶段被拒绝。
 */
@Configuration
@ConditionalOnProperty(prefix = "channel.email", name = "enabled", havingValue = "true")
public class EmailChannelConfig {

    @Bean
    public JavaMailSenderImpl emailJavaMailSender(EmailProperties props) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(props.getHost());
        sender.setPort(props.getPort());
        sender.setDefaultEncoding("UTF-8");
        if (props.getUsername() != null && !props.getUsername().isBlank()) {
            sender.setUsername(props.getUsername());
            sender.setPassword(props.getPassword());
        }
        sender.getJavaMailProperties().put("mail.smtp.connectiontimeout", String.valueOf(props.getTimeoutMs()));
        sender.getJavaMailProperties().put("mail.smtp.timeout", String.valueOf(props.getTimeoutMs()));
        sender.getJavaMailProperties().put("mail.smtp.writetimeout", String.valueOf(props.getTimeoutMs()));
        sender.getJavaMailProperties().put("mail.smtp.auth",
                String.valueOf(props.getUsername() != null && !props.getUsername().isBlank()));
        if (props.isSsl()) {
            sender.getJavaMailProperties().put("mail.smtp.ssl.enable", "true");
        }
        return sender;
    }

    /** 主动推送端口实现：core 只认 {@link MessageSender}，送达方式归渠道 */
    @Bean
    public MessageSender emailMessageSender(JavaMailSenderImpl emailJavaMailSender, EmailProperties props) {
        return new EmailMessageSender(emailJavaMailSender, props);
    }

    /** 健康状态：core 经 {@link ChannelHealth} 端口读取（编译期不可见本模块） */
    @Bean
    public ChannelHealth emailChannelHealth(EmailProperties props, MessageSender emailMessageSender) {
        return new EmailChannelHealth(props, (EmailMessageSender) emailMessageSender);
    }
}
