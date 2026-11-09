package com.dark.javaHarness.channel.feishu;

import com.dark.javaHarness.contract.spi.ChannelHealth;
import java.time.Clock;
import java.time.Duration;

/**
 * 飞书渠道健康：只读「最近一次投递结果」，不发主动探活。
 *
 * <p>为什么不探活：探活就得往群里发一条测试消息——为了确认「能发消息」而发一条假消息，
 * 是拿噪音换一个不可靠的信号。改用真实流量当探针，长时间没有投递就报「未知」。
 */
public class FeishuHealth implements ChannelHealth {

    /** 超过这个时长没有投递，状态就是「未知」而不是「可用」 */
    private static final Duration STALE_AFTER = Duration.ofDays(7);

    private final FeishuProperties props;
    private final FeishuWebhookSender sender;
    private final Clock clock;

    public FeishuHealth(FeishuProperties props, FeishuWebhookSender sender) {
        this(props, sender, Clock.systemUTC());
    }

    public FeishuHealth(FeishuProperties props, FeishuWebhookSender sender, Clock clock) {
        this.props = props;
        this.sender = sender;
        this.clock = clock;
    }

    @Override
    public String channelName() {
        return "飞书 / Webhook";
    }

    @Override
    public String channelId() {
        return "feishu";
    }

    @Override
    public Snapshot snapshot() {
        if (!props.isEnabled()) {
            return new Snapshot(false, false, "channel.feishu.enabled=false，飞书渠道未装配");
        }
        FeishuWebhookSender.Attempt last = sender.lastAttempt();
        if (last == null) {
            return new Snapshot(Status.UNKNOWN, "未知（尚无投递记录）");
        }
        Duration since = Duration.between(last.at(), clock.instant());
        if (since.compareTo(STALE_AFTER) > 0) {
            return new Snapshot(Status.UNKNOWN, "未知（最近一次投递在 " + since.toDays() + " 天前）");
        }
        // detail 不得出现 webhook URL / secret：监控页是浏览器能打开的页面
        return last.sent()
                ? new Snapshot(Status.UP, "最近一次投递成功")
                : new Snapshot(Status.DOWN, "最近一次投递失败：" + last.reason());
    }
}
