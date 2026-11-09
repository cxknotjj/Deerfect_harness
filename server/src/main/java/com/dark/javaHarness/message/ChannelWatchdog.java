package com.dark.javaHarness.message;

import com.dark.javaHarness.contract.spi.ChannelHealth;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 渠道探活告警：把「渠道能不能用」从一次性查询变成连续状态，并在状态翻转时通知你。
 *
 * <p>已有的 {@link ChannelHealth} 只被监控页读取——渠道断了要你自己打开网页才发现。
 * 本类补的就是这一段：定时探活 → 连续失败达阈值 → 发告警 → 恢复再发一条。
 *
 * <p><b>三条硬约束</b>：
 * <ol>
 *   <li>告警不能走故障渠道本身（不能用飞书报「飞书挂了」）。实现上在入队前把告警链里
 *       该渠道的段删掉；删完为空则只能记 ERROR 日志——此时已无可用通道。</li>
 *   <li>只对 {@code DOWN} 告警。{@code UNKNOWN}（如邮件 7 天没投递）是「不知道」，
 *       把它当故障会变成周期性假警报。</li>
 *   <li>故障持续期间不重复打扰：计数与抑制窗口复用 {@link AlertGate}，不另造一套状态机。</li>
 * </ol>
 *
 * <p>告警本身进 outbox（{@code source=alert}）而不是直接发：这样它和任务通知共享同一套
 * 重试与终态记录，发失败还能重投。代价是最多晚一个扫描节拍（默认 30 秒）送达。
 */
@Component
public class ChannelWatchdog {

    private static final Logger log = LoggerFactory.getLogger(ChannelWatchdog.class);

    /** outbox 的 source 取值，与 schedule 区分（排障时一眼看出是告警还是任务通知） */
    public static final String SOURCE = "alert";

    /** AlertGate 的 key 前缀：与投递告警共用同一个 bean，靠前缀隔离，避免误抑制 */
    private static final String KEY_PREFIX = "channel:";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectProvider<ChannelHealth> healths;
    private final DeliveryOutboxService outbox;
    private final AlertGate gate;
    private final boolean enabled;
    private final String alertChain;
    private final Clock clock;

    /** 未配告警落点只提示一次，否则每 5 分钟刷一条 warn */
    private volatile boolean warnedNoChain;

    // 显式标注：类里还有一个带 Clock 的测试构造器，Spring 需要在多个候选里明确选这个
    @Autowired
    public ChannelWatchdog(ObjectProvider<ChannelHealth> healths,
                           DeliveryOutboxService outbox,
                           AlertGate gate,
                           @Value("${app.channel-watchdog.enabled:true}") boolean enabled,
                           @Value("${app.channel-watchdog.alert-chain:}") String alertChain) {
        this(healths, outbox, gate, enabled, alertChain, Clock.systemDefaultZone());
    }

    /** 测试用：注入可控时钟，抑制窗口是小时级，真实等待没有意义 */
    public ChannelWatchdog(ObjectProvider<ChannelHealth> healths,
                           DeliveryOutboxService outbox,
                           AlertGate gate,
                           boolean enabled,
                           String alertChain,
                           Clock clock) {
        this.healths = healths;
        this.outbox = outbox;
        this.gate = gate;
        this.enabled = enabled;
        this.alertChain = alertChain == null ? "" : alertChain.trim();
        this.clock = clock;
    }

    /**
     * 调度节拍：异常全部吞在本层——探活实现抛异常不该影响其它 @Scheduled。
     */
    @Scheduled(fixedDelayString = "${app.channel-watchdog.interval-ms:300000}")
    public void tick() {
        try {
            int sent = scan().size();
            if (sent > 0) {
                log.info("[watchdog] 本轮产生 {} 条渠道通知", sent);
            }
        } catch (Exception e) {
            log.warn("[watchdog] 本轮探活异常，下轮继续: {}", e.toString());
        }
    }

    /**
     * 执行一轮探活。
     *
     * @return 本轮实际入队的通知（测试与排障用；空表示没有状态翻转或无处可发）
     */
    public List<Notice> scan() {
        if (!enabled) {
            return List.of();
        }
        if (alertChain.isEmpty()) {
            if (!warnedNoChain) {
                warnedNoChain = true;
                log.warn("[watchdog] 未配置 app.channel-watchdog.alert-chain，渠道故障只会记日志");
            }
            return List.of();
        }
        List<Notice> notices = new ArrayList<>();
        healths.orderedStream().forEach(health -> {
            try {
                Notice notice = check(health);
                if (notice != null) {
                    notices.add(notice);
                }
            } catch (Exception e) {
                // 单个渠道的探活实现出错，不能中断其它渠道的判定
                log.warn("[watchdog] 探活 {} 异常: {}", safeName(health), e.toString());
            }
        });
        return List.copyOf(notices);
    }

    /** 单个渠道的状态判定与通知 */
    private Notice check(ChannelHealth health) {
        ChannelHealth.Snapshot snapshot = health.snapshot();
        if (snapshot == null || snapshot.status() == ChannelHealth.Status.DISABLED) {
            // 渠道关掉时不参与判定：既不告警，也不保留计数（重新打开后从零开始）
            return null;
        }
        String channelId = channelIdOf(health);
        String key = KEY_PREFIX + channelId;
        return switch (snapshot.status()) {
            case UP -> gate.onSuccess(key)
                    ? deliver(channelId, NoticeKind.RECOVERED, recoveryText(health, channelId, snapshot))
                    : null;
            // 「不知道」不是「坏了」：无近期流量的渠道不该按故障处理
            case UNKNOWN -> null;
            case DOWN -> gate.onFailure(key)
                    ? deliver(channelId, NoticeKind.ALERT, alertText(health, channelId, snapshot))
                    : null;
            default -> null;
        };
    }

    /**
     * 入队一条通知；告警链里剔除故障渠道本身。
     *
     * @return 入队成功返回通知；无处可发（链被剔空）或入队异常返回 null
     */
    private Notice deliver(String channelId, NoticeKind kind, String text) {
        String chain = withoutChannel(alertChain, channelId);
        if (chain.isBlank()) {
            // 剩下的通道一个都没有：此刻除了日志确实没有别的办法，属已知残余风险
            log.error("[watchdog] 渠道 {} {}，但告警链 [{}] 只包含该渠道，通知发不出去",
                    channelId, kind == NoticeKind.ALERT ? "故障" : "恢复", alertChain);
            return null;
        }
        try {
            outbox.enqueue(SOURCE, channelId + "/" + kind.name(), chain, text);
            log.info("[watchdog] 渠道 {} {}，已入队通知（链={}）", channelId, kind, chain);
            return new Notice(channelId, kind, chain);
        } catch (Exception e) {
            log.warn("[watchdog] 渠道 {} 通知入队失败: {}", channelId, e.toString());
            return null;
        }
    }

    private String alertText(ChannelHealth health, String channelId, ChannelHealth.Snapshot snapshot) {
        return "【渠道告警】" + health.channelName() + " 当前不可用\n"
                + "- 渠道：" + channelId + "\n"
                + "- 连续失败：" + gate.consecutiveFailures(KEY_PREFIX + channelId) + " 次（每次定时探活计一次）\n"
                + "- 最近状态：" + snapshot.detail() + "\n"
                + "- 判定时间：" + TIME.format(clock.instant().atZone(clock.getZone())) + "\n"
                + "该渠道的任务通知会走降级链或留在投递队列里；恢复后会再发一条「已恢复」。";
    }

    private String recoveryText(ChannelHealth health, String channelId, ChannelHealth.Snapshot snapshot) {
        return "【渠道恢复】" + health.channelName() + " 已恢复\n"
                + "- 渠道：" + channelId + "\n"
                + "- 最近状态：" + snapshot.detail() + "\n"
                + "- 恢复时间：" + TIME.format(clock.instant().atZone(clock.getZone()));
    }

    /**
     * 从告警链里剔掉指定渠道的所有段。
     *
     * <p>为什么在链上剔而不是给发送加「排除参数」：outbox 的一条记录就是一个链，
     * 入队前定下来才能让重投也遵守「不走坏渠道」；否则每次重试都要记着排除谁。
     *
     * @return 剔除后的链；全部被剔除时返回空串
     */
    static String withoutChannel(String chain, String channelId) {
        if (chain == null || chain.isBlank()) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        for (String raw : chain.split("\\" + MessageSenderRegistry.CHAIN_SEPARATOR, -1)) {
            String segment = raw.trim();
            if (segment.isEmpty()) {
                continue;
            }
            int separator = segment.indexOf(':');
            String channel = separator <= 0 ? "" : segment.substring(0, separator).trim();
            if (!channelId.isBlank() && channel.equals(channelId)) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append(MessageSenderRegistry.CHAIN_SEPARATOR);
            }
            kept.append(segment);
        }
        return kept.toString();
    }

    /** 渠道标识；老实现没覆盖 channelId 时退回展示名（能计数，但无法排除本渠道） */
    private static String channelIdOf(ChannelHealth health) {
        String id = health.channelId();
        return id == null || id.isBlank() ? safeName(health) : id.trim();
    }

    private static String safeName(ChannelHealth health) {
        try {
            String name = health.channelName();
            return name == null || name.isBlank() ? "未知渠道" : name;
        } catch (Exception e) {
            return "未知渠道";
        }
    }

    /** 通知类型：决定 outbox 的 refId 与文案，也方便排障时区分「告警」与「恢复」 */
    public enum NoticeKind {
        ALERT,
        RECOVERED
    }

    /**
     * 一条已入队的渠道通知。
     *
     * @param channelId 故障 / 恢复的渠道标识
     * @param kind      告警还是恢复
     * @param chain     实际使用的告警链（已剔除本渠道）
     */
    public record Notice(String channelId, NoticeKind kind, String chain) {
    }
}
