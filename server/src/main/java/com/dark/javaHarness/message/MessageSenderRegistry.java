package com.dark.javaHarness.message;

import com.dark.javaHarness.contract.spi.MessageSender;
import com.dark.javaHarness.contract.spi.MessageSender.DeliveryReceipt;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 主动推送的渠道注册表：把 {@code channel:target} 形式的地址路由到对应渠道实现。
 *
 * <p>地址格式（存于 agent_schedule.notify_channel 与 delivery_outbox.channel_chain）：
 * <ul>
 *   <li>{@code qq:123456} —— 单通道（历史数据，行为与改造前一致）</li>
 *   <li>{@code feishu:main|email:me@example.com} —— 降级链，按序尝试</li>
 * </ul>
 *
 * <p>为什么不用事件：Goal 完成点在 AgentServiceImpl 有三处（直跑、流式成功、流式失败），
 * 发事件容易漏发或重发；改为「任务侧按 last_goal_id 轮询 goal 状态 + notified_at 幂等标记」，
 * 少一处对执行链的侵入，且失败天然下一轮重试。
 *
 * <p><b>两种解析的职责不同，不要合并</b>：{@link #chainOf} 严格校验、抛异常，用于「入队前」
 * 把配置错误当场暴露；{@link #sendWithFallback} 宽松解析、绝不抛，用于「发送时」——
 * 那一刻已经在投递途中，任何异常都只能变成一条失败记录，不能炸掉调度循环。
 */
@Component
public class MessageSenderRegistry {

    private static final Logger log = LoggerFactory.getLogger(MessageSenderRegistry.class);

    /** 降级链分隔符 */
    public static final String CHAIN_SEPARATOR = "|";

    private final Map<String, MessageSender> byChannel;

    public MessageSenderRegistry(List<MessageSender> senders) {
        Map<String, MessageSender> map = new LinkedHashMap<>();
        for (MessageSender sender : senders) {
            MessageSender previous = map.putIfAbsent(sender.channel(), sender);
            if (previous != null) {
                // 同渠道多个实现：先注册者优先，暴露出来避免「发到了另一个实现」的静默错乱
                log.warn("[message] 渠道 {} 存在多个实现，忽略 {}", sender.channel(), sender.getClass().getName());
            }
        }
        this.byChannel = Map.copyOf(map);
        log.info("[message] 主动推送渠道: {}", byChannel.keySet());
    }

    /**
     * 按 {@code channel:target} 发送（单通道语义）；地址非法、渠道未注册、实现抛异常都返回 false。
     *
     * <p>含降级链时只试第一段——保留这个方法是为了让改造前的调用方语义不变。
     * 需要降级请用 {@link #sendWithFallback}。
     */
    public boolean send(String channelRef, String text) {
        if (channelRef == null || channelRef.isBlank() || text == null || text.isBlank()) {
            return false;
        }
        return sendOnce(firstSegment(channelRef), text).sent();
    }

    /**
     * 解析并校验降级链，供「入队前」调用——配置错误当场报，不等真的触发任务才在日志里发现。
     *
     * @return 逐段 {@code channel:target}（保持原顺序）
     * @throws IllegalArgumentException 地址为空、含空段、缺 target、渠道未注册
     */
    public List<String> chainOf(String channelRef) {
        if (channelRef == null || channelRef.isBlank()) {
            throw new IllegalArgumentException("推送地址为空");
        }
        String[] parts = channelRef.split("\\" + CHAIN_SEPARATOR, -1);
        List<String> chain = new ArrayList<>(parts.length);
        for (String part : parts) {
            String segment = part == null ? "" : part.trim();
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("通道链含空段：" + channelRef);
            }
            chain.add(validateSegment(segment, channelRef));
        }
        return List.copyOf(chain);
    }

    /**
     * 按链逐跳尝试，返回每一跳的结果（顺序与链一致，成功后不再尝试后续）。
     *
     * <p>绝不抛异常：投递途中任何问题都只能变成一条失败记录。
     *
     * @param excludeChannel 跳过该渠道（告警时传故障渠道——不能用坏渠道去报坏渠道）
     */
    public List<Attempt> sendWithFallback(String channelRef, String text, String excludeChannel) {
        if (channelRef == null || channelRef.isBlank() || text == null || text.isBlank()) {
            return List.of(new Attempt(String.valueOf(channelRef), false, "地址或内容为空", false));
        }
        List<Attempt> results = new ArrayList<>();
        for (String raw : channelRef.split("\\" + CHAIN_SEPARATOR, -1)) {
            String segment = raw == null ? "" : raw.trim();
            if (segment.isEmpty()) {
                results.add(new Attempt("", false, "通道链含空段", false));
                continue;
            }
            String channel = channelOf(segment);
            if (excludeChannel != null && !excludeChannel.isBlank() && excludeChannel.equals(channel)) {
                results.add(new Attempt(segment, false, "按 excludeChannel 跳过", false));
                continue;
            }
            Attempt attempt = sendOnce(segment, text);
            results.add(attempt);
            if (attempt.sent()) {
                break;
            }
        }
        return List.copyOf(results);
    }

    /**
     * 逐跳尝试并返回第一个成功的结果；全失败时返回最后一跳（错误信息最接近真实原因）。
     *
     * <p>给 outbox 用：它只关心「成了没 / 为什么没成」，不需要看完整链路。
     */
    public Attempt sendFirstSuccess(String channelRef, String text, String excludeChannel) {
        List<Attempt> results = sendWithFallback(channelRef, text, excludeChannel);
        for (Attempt attempt : results) {
            if (attempt.sent()) {
                return attempt;
            }
        }
        return results.isEmpty()
                ? new Attempt(String.valueOf(channelRef), false, "链路为空", false)
                : results.get(results.size() - 1);
    }

    /** 已注册的渠道标识（排障与监控用） */
    public Set<String> channels() {
        return byChannel.keySet();
    }

    /**
     * 单段发送一次：解析 → 查渠道 → 调实现 → 兜异常。所有失败都折成 {@link Attempt}。
     *
     * <p>给 outbox 用——它需要「只发当前这一跳」，而不是一次跑完整条链
     * （后者会让每跳的重试次数失去意义）。
     */
    public Attempt sendOnce(String channelRef, String text) {
        String segment = channelRef == null ? "" : channelRef.trim();
        int separator = segment.indexOf(':');
        if (separator <= 0 || separator == segment.length() - 1) {
            log.warn("[message] 推送地址非法（应为 channel:target）: {}", segment);
            return new Attempt(segment, false, "推送地址非法（应为 channel:target）", false);
        }
        String channel = segment.substring(0, separator).trim();
        String target = segment.substring(separator + 1).trim();
        MessageSender sender = byChannel.get(channel);
        if (sender == null) {
            log.warn("[message] 渠道未注册，推送失败 channel={}（已注册: {}）", channel, byChannel.keySet());
            return new Attempt(segment, false, "渠道未注册：" + channel, false);
        }
        try {
            DeliveryReceipt receipt = sender.sendForReceipt(target, text);
            if (receipt == null) {
                log.warn("[message] 渠道 {} 返回空回执，按未送达处理", channel);
                return new Attempt(segment, false, "渠道返回空回执", true);
            }
            if (!receipt.sent()) {
                log.warn("[message] 推送未送达 channel={} target={} 原因={}", channel, target, receipt.reason());
            }
            return new Attempt(segment, receipt.sent(), receipt.reason(), receipt.retryable());
        } catch (Exception e) {
            // 实现方按约定不抛，这里兜住第三方实现违约，避免把推送失败升级成任务失败
            log.warn("[message] 推送异常 channel={} target={}: {}", channel, target, e.toString());
            return new Attempt(segment, false, "推送异常：" + e.getMessage(), true);
        }
    }

    private String validateSegment(String segment, String channelRef) {
        int separator = segment.indexOf(':');
        if (separator <= 0 || separator == segment.length() - 1) {
            throw new IllegalArgumentException("推送地址非法（应为 channel:target）：" + segment + "（链：" + channelRef + "）");
        }
        String channel = segment.substring(0, separator).trim();
        if (!byChannel.containsKey(channel)) {
            throw new IllegalArgumentException(
                    "渠道未注册：" + channel + "（已注册：" + byChannel.keySet() + "，链：" + channelRef + "）");
        }
        return segment;
    }

    private static String channelOf(String segment) {
        int separator = segment.indexOf(':');
        return separator <= 0 ? "" : segment.substring(0, separator).trim();
    }

    private static String firstSegment(String channelRef) {
        int index = channelRef.indexOf(CHAIN_SEPARATOR);
        return index < 0 ? channelRef.trim() : channelRef.substring(0, index).trim();
    }

    /**
     * 单跳结果。
     *
     * @param channelRef 该跳的原文（{@code channel:target}）
     * @param sent       是否送达
     * @param reason     失败原因；成功时为 null
     * @param retryable  失败是否值得重试（渠道明确标记为配置错 / 内容问题时为 false）
     */
    public record Attempt(String channelRef, boolean sent, String reason, boolean retryable) {
    }
}
