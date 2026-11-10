package com.dark.javaHarness.channel.feishu.ws;

import com.dark.javaHarness.channel.feishu.FeishuProperties;
import com.dark.javaHarness.contract.spi.ChannelHealth;
import java.time.Clock;
import java.time.Duration;

/**
 * 飞书<b>长连接</b>的健康快照（入站链路），与 M3a 的 webhook 健康分开。
 *
 * <p><b>为什么单独一条</b>：webhook 的状态回答「能不能往群里发通知」，长连接的状态回答
 * 「能不能收到消息」。两条链路用的凭证与端点都不同，混成一条会让「webhook 正常」掩盖
 * 「长连接已死」——而后者恰恰是最不容易被发现的一种：用户发消息没反应，服务端一片安静。
 *
 * <p><b>判据只能是「最近一次收到事件」</b>：WS 没有可主动探测的接口（不像 HTTP），
 * 发测试消息又要先有会话。所以：从未收到事件 → 未知；超过 7 天没有事件 → 未知；
 * 只有「运行中 + 近期有事件」才算可用。<b>刻意不把「长时间没事件」判成故障</b>——
 * 一个安静的机器人一周没人找它是正常的，判成 DOWN 只会变成周期性假警报。
 *
 * <p>{@code channelId} 用 {@code feishu-ws} 而不是 {@code feishu}：探活告警按渠道 id
 * 计数与抑制，与 webhook 共用 id 会让两条链路的失败计数互相污染。
 */
public class FeishuWsHealth implements ChannelHealth {

    /** 超过这个时长没有事件，状态降为「未知」而不是「可用」 */
    private static final Duration STALE_AFTER = Duration.ofDays(7);

    private final FeishuProperties props;
    private final FeishuWsClient client;
    private final FeishuInboundService inbound;
    private final Clock clock;

    public FeishuWsHealth(FeishuProperties props, FeishuWsClient client, FeishuInboundService inbound) {
        this(props, client, inbound, Clock.systemUTC());
    }

    /** 测试用：注入固定时钟，7 天窗口没法真等 */
    public FeishuWsHealth(FeishuProperties props, FeishuWsClient client, FeishuInboundService inbound,
                          Clock clock) {
        this.props = props;
        this.client = client;
        this.inbound = inbound;
        this.clock = clock;
    }

    @Override
    public String channelName() {
        return "飞书 / 长连接";
    }

    @Override
    public String channelId() {
        return "feishu-ws";
    }

    @Override
    public Snapshot snapshot() {
        if (!props.getWs().isEnabled()) {
            return new Snapshot(Status.DISABLED, "channel.feishu.ws.enabled=false，长连接未装配");
        }
        if (!client.isRunning()) {
            // 启动失败或已停止：这时一定收不到消息，属于确定的故障
            return new Snapshot(Status.DOWN, "长连接未运行（启动失败或已停止）");
        }
        long last = inbound.lastEventAtMillis();
        if (last <= 0) {
            return new Snapshot(Status.UNKNOWN, "运行中，但自启动以来未收到任何事件");
        }
        Duration since = Duration.ofMillis(clock.millis() - last);
        if (since.compareTo(STALE_AFTER) > 0) {
            return new Snapshot(Status.UNKNOWN, "运行中，最近一次事件在 " + since.toDays() + " 天前");
        }
        return new Snapshot(Status.UP, "运行中，" + ago(since) + "收到过事件");
    }

    /** 人读的相对时间；健康详情会被直接放进告警正文，别用 123456ms 这种形式 */
    private static String ago(Duration since) {
        long seconds = Math.max(0, since.toSeconds());
        if (seconds < 60) {
            return seconds + " 秒前";
        }
        if (seconds < 3600) {
            return (seconds / 60) + " 分钟前";
        }
        if (seconds < 86_400) {
            return (seconds / 3600) + " 小时前";
        }
        return (seconds / 86_400) + " 天前";
    }
}
