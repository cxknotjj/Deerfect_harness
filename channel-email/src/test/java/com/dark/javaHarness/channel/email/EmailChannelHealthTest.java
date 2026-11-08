package com.dark.javaHarness.channel.email;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * 邮件渠道健康单测：不做 SMTP 探活，用「最近一次投递」当探针。
 *
 * <p>核心判断是「不知道就说不知道」——超 7 天没投递报「未知」，而不是因为配置了
 * 就默认健康。假装健康的下场是看板上一切正常、实际一条都发不出去。
 */
class EmailChannelHealthTest {

    private static final Instant NOW = Instant.parse("2026-01-10T00:00:00Z");

    private static EmailProperties enabledProps() {
        EmailProperties props = new EmailProperties();
        props.setEnabled(true);
        props.setFrom("bot@example.com");
        return props;
    }

    private static EmailMessageSender senderAt(Instant at, JavaMailSenderImpl mailSender) {
        return new EmailMessageSender(mailSender, enabledProps(), Clock.fixed(at, ZoneOffset.UTC));
    }

    private static EmailChannelHealth healthAt(EmailProperties props, EmailMessageSender sender) {
        return new EmailChannelHealth(props, sender, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void disabled_reportsNotEnabled() {
        EmailProperties props = new EmailProperties();
        props.setEnabled(false);

        EmailChannelHealth.Snapshot snapshot = healthAt(props, senderAt(NOW, mock(JavaMailSenderImpl.class))).snapshot();

        assertFalse(snapshot.enabled());
        assertFalse(snapshot.available());
        assertTrue(snapshot.detail().contains("enabled=false"), snapshot.detail());
    }

    @Test
    void neverSent_reportsUnknownNotHealthy() {
        EmailProperties props = enabledProps();
        EmailMessageSender sender = senderAt(NOW, mock(JavaMailSenderImpl.class));

        EmailChannelHealth.Snapshot snapshot = healthAt(props, sender).snapshot();

        assertTrue(snapshot.enabled());
        assertFalse(snapshot.available(), "没投递过就不能报「可用」");
        assertTrue(snapshot.detail().contains("尚无投递记录"), snapshot.detail());
    }

    @Test
    void recentSuccess_reportsAvailable() {
        EmailProperties props = enabledProps();
        JavaMailSenderImpl mailSender = mock(JavaMailSenderImpl.class);
        EmailMessageSender sender = senderAt(NOW.minusSeconds(60), mailSender);
        sender.sendForReceipt("me@example.com", "内容");

        EmailChannelHealth.Snapshot snapshot = healthAt(props, sender).snapshot();

        assertTrue(snapshot.available());
        assertTrue(snapshot.detail().contains("成功"), snapshot.detail());
    }

    @Test
    void recentFailure_reportsUnavailableWithReason() {
        EmailProperties props = enabledProps();
        JavaMailSenderImpl mailSender = mock(JavaMailSenderImpl.class);
        doThrow(new org.springframework.mail.MailSendException("Connection timed out"))
                .when(mailSender).send(any(SimpleMailMessage.class));
        EmailMessageSender sender = senderAt(NOW.minusSeconds(60), mailSender);
        sender.sendForReceipt("me@example.com", "内容");

        EmailChannelHealth.Snapshot snapshot = healthAt(props, sender).snapshot();

        assertFalse(snapshot.available());
        assertTrue(snapshot.detail().contains("失败"), snapshot.detail());
    }

    @Test
    void staleSuccess_reportsUnknownAfterSevenDays() {
        EmailProperties props = enabledProps();
        JavaMailSenderImpl mailSender = mock(JavaMailSenderImpl.class);
        EmailMessageSender sender = senderAt(NOW.minusSeconds(8 * 24 * 3600L), mailSender);
        sender.sendForReceipt("me@example.com", "内容");

        EmailChannelHealth.Snapshot snapshot = healthAt(props, sender).snapshot();

        assertFalse(snapshot.available(), "8 天没投递，状态已经不代表当前可用性");
        assertTrue(snapshot.detail().contains("未知"), snapshot.detail());
    }

    @Test
    void channelName_isReadable() {
        assertTrue(healthAt(enabledProps(), senderAt(NOW, mock(JavaMailSenderImpl.class)))
                .channelName().contains("邮件"));
    }
}
