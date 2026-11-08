package com.dark.javaHarness.channel.email;

import com.dark.javaHarness.service.MessageSender;
import jakarta.mail.SendFailedException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * 邮件渠道的主动推送实现：把「一条通知」投到指定邮箱。
 *
 * <p>正文是纯文本 + 固定头部（时间 / 来源 / 目标），不做 HTML——纯文本更不容易被判垃圾，
 * 也不用维护模板。
 *
 * <p>失败分成两类，这是 outbox 唯一能依据的补救线索：
 * <ul>
 *   <li><b>不可重试</b>：认证失败、发件人未配置、收件人无效——重试只会一直失败，
 *       还可能触发服务商风控</li>
 *   <li><b>可重试</b>：连接超时、服务端 5xx、其它未知异常——下一轮退避再试</li>
 * </ul>
 *
 * <p>最近一次投递结果会留在实例上供 {@link EmailChannelHealth} 读取：SMTP 探活要建 TCP +
 * 认证，成本远高于 HTTP，所以这里用「真实流量当探针」，没有流量就报「未知」而不是假装健康。
 */
public class EmailMessageSender implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(EmailMessageSender.class);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 主题里放正文首行，截断避免超长主题被判异常 */
    private static final int SUBJECT_PREVIEW = 40;

    private final JavaMailSenderImpl mailSender;
    private final EmailProperties props;
    private final Clock clock;

    private volatile Attempt lastAttempt;

    public EmailMessageSender(JavaMailSenderImpl mailSender, EmailProperties props) {
        this(mailSender, props, Clock.systemUTC());
    }

    /** 测试用：注入可控时钟，避免用真实时间判断「是否超过 7 天无投递」 */
    public EmailMessageSender(JavaMailSenderImpl mailSender, EmailProperties props, Clock clock) {
        this.mailSender = mailSender;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public String channel() {
        return "email";
    }

    @Override
    public boolean send(String target, String text) {
        return sendForReceipt(target, text).sent();
    }

    @Override
    public DeliveryReceipt sendForReceipt(String target, String text) {
        Instant at = clock.instant();
        if (target == null || target.isBlank()) {
            return record(at, DeliveryReceipt.fatal("收件人为空"));
        }
        if (text == null || text.isBlank()) {
            return record(at, DeliveryReceipt.fatal("正文为空"));
        }
        if (props.getFrom() == null || props.getFrom().isBlank()) {
            return record(at, DeliveryReceipt.fatal("未配置 channel.email.from"));
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(props.getFrom().trim());
            message.setTo(target.trim());
            message.setSubject(subjectOf(text));
            message.setText(bodyOf(target, text));
            mailSender.send(message);
            log.info("[email-sender] 送达 to={}", target);
            return record(at, DeliveryReceipt.sent(null));
        } catch (MailAuthenticationException e) {
            // 认证失败重试一万次也不会好，还会触发服务商的异常登录保护
            return record(at, DeliveryReceipt.fatal("SMTP 认证失败：" + brief(e)));
        } catch (MailSendException e) {
            if (isInvalidRecipient(e)) {
                return record(at, DeliveryReceipt.fatal("收件人无效：" + brief(e)));
            }
            return record(at, DeliveryReceipt.failed("SMTP 发送失败：" + brief(e)));
        } catch (MailException e) {
            return record(at, DeliveryReceipt.failed("SMTP 异常：" + brief(e)));
        } catch (Exception e) {
            return record(at, DeliveryReceipt.failed("发送异常：" + brief(e)));
        }
    }

    /** 最近一次投递（供健康检查读；从未投递返回 null） */
    public Attempt lastAttempt() {
        return lastAttempt;
    }

    private DeliveryReceipt record(Instant at, DeliveryReceipt receipt) {
        this.lastAttempt = new Attempt(at, receipt.sent(), receipt.reason());
        return receipt;
    }

    private static String subjectOf(String text) {
        String firstLine = text.strip().split("\\R", 2)[0].strip();
        if (firstLine.isEmpty()) {
            return "【java-harness】通知";
        }
        String preview = firstLine.length() <= SUBJECT_PREVIEW
                ? firstLine
                : firstLine.substring(0, SUBJECT_PREVIEW) + "…";
        return "【java-harness】" + preview;
    }

    private String bodyOf(String target, String text) {
        return "来源：java-harness\r\n"
                + "目标：" + target + "\r\n"
                + "时间：" + LocalDateTime.now(clock).format(TIME) + "\r\n"
                + "----------\r\n"
                + text;
    }

    /** 收件人被拒（SendFailedException 里带 invalid addresses）属配置错，重试无意义 */
    private static boolean isInvalidRecipient(MailSendException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SendFailedException failed
                    && failed.getInvalidAddresses() != null
                    && failed.getInvalidAddresses().length > 0) {
                return true;
            }
        }
        String message = e.getMessage();
        return message != null && message.contains("Invalid Addresses");
    }

    /** 异常摘要：只要类型 + 简短消息，避免把整个 SMTP 会话日志写进 last_error */
    private static String brief(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        String firstLine = message.strip().split("\\R", 2)[0].strip();
        return firstLine.length() <= 200 ? firstLine : firstLine.substring(0, 200) + "…";
    }

    /**
     * 一次投递的结果。
     *
     * @param at     投递时刻
     * @param sent   是否送达
     * @param reason 失败原因（成功时为 null）
     */
    public record Attempt(Instant at, boolean sent, String reason) {
    }
}
