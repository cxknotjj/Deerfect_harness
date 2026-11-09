package com.dark.javaHarness.channel.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.dark.javaHarness.contract.spi.MessageSender.DeliveryReceipt;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * 邮件渠道单测：成功、四类失败分流、参数校验。
 *
 * <p>重点是「失败分类」——认证失败 / 收件人无效必须判为不可重试（重试只会一直失败，
 * 还可能触发服务商风控），连接超时才是可重试。这个区分是 outbox 唯一能依据的补救线索。
 */
@ExtendWith(MockitoExtension.class)
class EmailMessageSenderTest {

    @Mock
    private JavaMailSenderImpl mailSender;

    private EmailProperties props;
    private EmailMessageSender sender;

    @BeforeEach
    void setUp() {
        props = new EmailProperties();
        props.setEnabled(true);
        props.setFrom("bot@example.com");
        props.setUsername("bot@example.com");
        props.setPassword("secret");
        sender = new EmailMessageSender(mailSender, props,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void send_success_carriesSubjectAndBody() {
        DeliveryReceipt receipt = sender.sendForReceipt("me@example.com", "【定时任务】每日早报\n结果：成功");

        assertTrue(receipt.sent());
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertEquals("bot@example.com", sent.getFrom());
        assertEquals("me@example.com", sent.getTo()[0]);
        assertTrue(sent.getSubject().contains("每日早报"), sent.getSubject());
        assertTrue(sent.getText().contains("结果：成功"));
        assertTrue(sent.getText().contains("目标：me@example.com"), "头部要能看出这条是谁发的");
    }

    @Test
    void send_authenticationFailure_isFatal() {
        doThrow(new MailAuthenticationException("535 auth failed")).when(mailSender).send(any(SimpleMailMessage.class));

        DeliveryReceipt receipt = sender.sendForReceipt("me@example.com", "内容");

        assertFalse(receipt.sent());
        assertFalse(receipt.retryable(), "认证失败重试一万次也不会好");
        assertTrue(receipt.reason().contains("认证失败"), receipt.reason());
    }

    @Test
    void send_invalidRecipient_isFatal() {
        doThrow(new MailSendException("Invalid Addresses")).when(mailSender).send(any(SimpleMailMessage.class));

        DeliveryReceipt receipt = sender.sendForReceipt("nobody@example.com", "内容");

        assertFalse(receipt.sent());
        assertFalse(receipt.retryable(), "收件人不存在属于配置错");
        assertTrue(receipt.reason().contains("收件人无效"), receipt.reason());
    }

    @Test
    void send_transientFailure_isRetryable() {
        doThrow(new MailSendException("Connection timed out")).when(mailSender).send(any(SimpleMailMessage.class));

        DeliveryReceipt receipt = sender.sendForReceipt("me@example.com", "内容");

        assertFalse(receipt.sent());
        assertTrue(receipt.retryable(), "超时是瞬时的，下一轮退避再试");
    }

    @Test
    void send_blankTarget_fatalWithoutCallingSmtp() {
        DeliveryReceipt receipt = sender.sendForReceipt("  ", "内容");

        assertFalse(receipt.sent());
        assertFalse(receipt.retryable());
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void send_blankText_fatal() {
        DeliveryReceipt receipt = sender.sendForReceipt("me@example.com", " ");

        assertFalse(receipt.sent());
        assertTrue(receipt.reason().contains("正文为空"), receipt.reason());
    }

    @Test
    void send_missingFrom_fatal() {
        props.setFrom(" ");

        DeliveryReceipt receipt = sender.sendForReceipt("me@example.com", "内容");

        assertFalse(receipt.sent());
        assertFalse(receipt.retryable());
        assertTrue(receipt.reason().contains("from"), receipt.reason());
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void send_longFirstLine_subjectTruncated() {
        String longLine = "标题".repeat(40);

        sender.sendForReceipt("me@example.com", longLine + "\n正文");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        String subject = captor.getValue().getSubject();
        assertTrue(subject.length() < 60, "主题过长容易被判异常：" + subject.length());
        assertTrue(subject.endsWith("…"), subject);
    }

    @Test
    void lastAttempt_recordsFailureReason() {
        doThrow(new MailSendException("Connection timed out")).when(mailSender).send(any(SimpleMailMessage.class));

        sender.sendForReceipt("me@example.com", "内容");

        EmailMessageSender.Attempt last = sender.lastAttempt();
        assertFalse(last.sent());
        assertTrue(last.reason().contains("SMTP"), last.reason());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), last.at());
    }
}
