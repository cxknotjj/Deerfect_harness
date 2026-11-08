package com.dark.javaHarness.channel.email;

import com.dark.javaHarness.channel.ChannelHealth;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 邮件渠道健康：只读「最近一次投递结果」，不做 SMTP 探活。
 *
 * <p>为什么不做主动探活：每次探活要建一次 TCP + 认证，成本远高于 HTTP 探活，还会占用
 * 服务商的连接额度。改用「真实流量当探针」——有投递就报它的结果，长时间没有投递就报
 * 「未知」而不是假装健康。假装健康比报错更糟：它让你在看板上以为一切正常。
 */
public class EmailChannelHealth implements ChannelHealth {

    /** 超过这个时长没有投递，状态就是「未知」而不是「可用」 */
    private static final Duration STALE_AFTER = Duration.ofDays(7);

    private final EmailProperties props;
    private final EmailMessageSender sender;
    private final Clock clock;

    public EmailChannelHealth(EmailProperties props, EmailMessageSender sender) {
        this(props, sender, Clock.systemUTC());
    }

    public EmailChannelHealth(EmailProperties props, EmailMessageSender sender, Clock clock) {
        this.props = props;
        this.sender = sender;
        this.clock = clock;
    }

    @Override
    public String channelName() {
        return "邮件 / SMTP";
    }

    @Override
    public String channelId() {
        return "email";
    }

    @Override
    public Snapshot snapshot() {
        if (!props.isEnabled()) {
            return new Snapshot(false, false, "channel.email.enabled=false，邮件渠道未装配");
        }
        EmailMessageSender.Attempt last = sender.lastAttempt();
        if (last == null) {
            // UNKNOWN 而不是 DOWN：没发过信不等于发不出去，探活告警不该据此打扰
            return new Snapshot(Status.UNKNOWN, "未知（尚无投递记录）");
        }
        Duration since = Duration.between(last.at(), clock.instant());
        if (since.compareTo(STALE_AFTER) > 0) {
            return new Snapshot(Status.UNKNOWN, "未知（最近一次投递在 " + since.toDays() + " 天前）");
        }
        return last.sent()
                ? new Snapshot(true, true, "最近一次投递成功")
                : new Snapshot(true, false, "最近一次投递失败：" + last.reason());
    }
}
